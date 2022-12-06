package pl.touk.nussknacker.k8s.manager

import com.typesafe.config.ConfigValueFactory.{fromAnyRef, fromIterable, fromMap}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.Inspectors.forAll
import org.scalatest.tags.Network
import org.scalatest.{Assertion, OptionValues}
import pl.touk.nussknacker.engine.api.ProcessVersion
import pl.touk.nussknacker.engine.api.deployment.StateStatus
import pl.touk.nussknacker.engine.api.deployment.simple.SimpleStateStatus
import pl.touk.nussknacker.engine.api.process.{EmptyProcessConfigCreator, ProcessId, ProcessName, VersionId}
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.deployment.DeploymentData
import pl.touk.nussknacker.engine.spel.Implicits._
import pl.touk.nussknacker.engine.testing.LocalModelData
import pl.touk.nussknacker.k8s.manager.K8sDeploymentManager.requirementForName
import pl.touk.nussknacker.k8s.manager.K8sPodsResourceQuotaChecker.ResourceQuotaExceededException
import pl.touk.nussknacker.test.{EitherValuesDetailedMessage, KafkaConfigProperties}
import skuber.Container.Port
import skuber.LabelSelector.dsl._
import skuber.Resource.{Quantity, Quota}
import skuber.json.format._
import skuber.{ConfigMap, EnvVar, ListResource, ObjectMeta, Pod, Resource, Volume}
import sttp.client.{HttpURLConnectionBackend, Identity, NothingT, SttpBackend, _}

import java.nio.file.Files
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.reflectiveCalls
import scala.util.Random

// we use this tag to mark tests using external dependencies
@Network
class K8sDeploymentManagerKafkaTest extends BaseK8sDeploymentManagerTest
  with OptionValues with EitherValuesDetailedMessage with LazyLogging {

  private lazy val kafka = new KafkaK8sSupport(k8s)
  private implicit val backend: SttpBackend[Identity, Nothing, NothingT] = HttpURLConnectionBackend()

  test("deployment of kafka ping-pong") {
    val f = createKafkaFixture()
    f.withRunningScenario {
      val message = """{"message":"Nussknacker!"}"""
      kafka.sendToTopic(f.inputTopic, message)
      kafka.readFromTopic(f.outputTopic, 1) shouldBe List(message)
    }
  }

  test("redeployment of ping-pong") {
    //we append random to make it easier to test with reused kafka deployment
    val seed = new Random().nextInt()
    val input = s"ping-$seed"
    val output = s"pong-$seed"
    kafka.createTopic(input)
    kafka.createTopic(output)

    val manager = prepareManager()

    def deployScenario(version: Int) = {
      val scenario = ScenarioBuilder
        .streamingLite("foo scenario \u2620")
        .source("source", "kafka", "Topic" -> s"'$input'", "Schema version" -> "'latest'")
        .emptySink("sink", "kafka", "Topic" -> s"'$output'", "Schema version" -> "'latest'", "Key" -> "", "Raw editor" -> "true", "Value validation mode" -> "'strict'",
          "Value" -> s"{ original: #input, version: $version }")

      val pversion = ProcessVersion(VersionId(version), ProcessName(scenario.id), ProcessId(1234), "testUser", Some(22))
      manager.deploy(pversion, DeploymentData.empty, scenario, None).futureValue
      pversion
    }

    def waitForRunning(version: ProcessVersion) = {
      eventually {
        val state = manager.findJobStatus(version.processName).futureValue
        state.flatMap(_.version) shouldBe Some(version)
        state.map(_.status) shouldBe Some(SimpleStateStatus.Running)
      }
    }

    val message = """{"message":"Nussknacker!"}"""

    def messageForVersion(version: Int) = s"""{"original":$message,"version":$version}"""

    kafka.createSchema(s"$input-value", defaultSchema)
    kafka.createSchema(s"$output-value", s"""{"type":"object","properties":{"original":$defaultSchema,"version":{"type":"number"}}}""")

    val version1 = deployScenario(1)
    waitForRunning(version1)

    kafka.sendToTopic(input, message)
    kafka.readFromTopic(output, 1) shouldBe List(messageForVersion(1))

    val version2 = deployScenario(2)
    waitForRunning(version2)

    kafka.sendToTopic(input, message)
    kafka.readFromTopic(output, 2) shouldBe List(messageForVersion(1), messageForVersion(2))

    cancelAndAssertCleanup(manager, version2)
  }

  test("should redeploy during deploy") {
    //we append random to make it easier to test with reused kafka deployment
    val seed = new Random().nextInt()
    val inputTopic = s"in-$seed"
    val outputTopic = s"out1-$seed"
    val otherOutputTopic = s"out2-$seed"
    List(inputTopic, outputTopic, otherOutputTopic).foreach(kafka.createTopic)

    val manager = prepareManager()

    def scenarioWithOutputTo(topicName: String) = ScenarioBuilder
      .streamingLite("foo scenario \u2620")
      .source("source", "kafka", "Topic" -> s"'$inputTopic'", "Schema version" -> "'latest'")
      .emptySink("sink", "kafka", "Topic" -> s"'$topicName'", "Schema version" -> "'latest'", "Key" -> "", "Raw editor" -> "true", "Value validation mode" -> "'strict'",
        "Value" -> "#input")

    def waitFor(version: ProcessVersion) = {
      class InStateAssertionHelper {
        def inState(stateStatus: StateStatus): Assertion = eventually {
          val state = manager.findJobStatus(version.processName).futureValue
          state.flatMap(_.version) shouldBe Some(version)
          state.map(_.status) shouldBe Some(stateStatus)
        }
      }
      new InStateAssertionHelper()
    }

    val scenario = scenarioWithOutputTo(outputTopic)
    val version = ProcessVersion(VersionId(11), ProcessName(scenario.id), ProcessId(1234), "testUser", Some(22))

    kafka.createSchema(s"$inputTopic-value", defaultSchema)
    kafka.createSchema(s"$outputTopic-value", defaultSchema)
    kafka.createSchema(s"$otherOutputTopic-value", defaultSchema)

    val message = """{"message":"Nussknacker!"}"""
    kafka.sendToTopic(inputTopic, message)

    val otherVersion = version.copy(versionId = VersionId(12), modelVersion = Some(23))
    val otherScenario = scenarioWithOutputTo(otherOutputTopic)
    manager.deploy(version, DeploymentData.empty, scenario, None).futureValue
    waitFor(version).inState(SimpleStateStatus.DuringDeploy)

    val oldPod = k8s.listSelected[ListResource[Pod]](requirementForName(version.processName)).futureValue.items.head

    manager.deploy(otherVersion, DeploymentData.empty, otherScenario, None).futureValue

    var statuses: List[StateStatus] = Nil
    // wait until new pod arrives..
    eventually {
      val newPod = k8s.listSelected[ListResource[Pod]](requirementForName(version.processName)).futureValue.items.head
      if (newPod.metadata.name == oldPod.metadata.name) {
        statuses = statuses ::: manager.findJobStatus(otherVersion.processName).futureValue.get.status :: Nil
      }
      newPod.metadata.name should not be oldPod.metadata.name
    }
    //..and make sure scenario status was never Running to this point
    statuses should contain only SimpleStateStatus.DuringDeploy

    waitFor(otherVersion).inState(SimpleStateStatus.Running)
    kafka.readFromTopic(otherOutputTopic, 1) shouldBe List(message)
    manager.cancel(otherVersion.processName, DeploymentData.systemUser).futureValue
    eventually {
      manager.findJobStatus(version.processName).futureValue shouldBe None
    }
    //should not fail
    cancelAndAssertCleanup(manager, version)
  }


  test("should deploy scenario with env, resources and replicas count from k8sDeploymentConfig") {
    val f = createKafkaFixture(
      deployConfig = kafkaDeployConfig
        .withValue("k8sDeploymentConfig.spec.replicas", fromAnyRef(3))
        .withValue("k8sDeploymentConfig.spec.template.spec.containers",
          fromIterable(List(
            fromMap(Map(
              "name" -> "runtime",
              "image" -> s"touk/nussknacker-lite-runtime-app:$dockerTag",
              "env" -> fromIterable(List(
                fromMap(
                  Map(
                    "name" -> "ENV_VARIABLE",
                    "value" -> "VALUE"
                  ).asJava
                )
              ).asJava),
              "resources" -> fromMap(
                Map(
                  "requests" -> fromMap(Map("memory" -> "256Mi", "cpu" -> "800m").asJava),
                  "limits" -> fromMap(Map("memory" -> "256Mi", "cpu" -> "800m").asJava)
                ).asJava
              )
            ).asJava)
          ).asJava)
        )
    )
    f.withRunningScenario {
      eventually {
        val pods = k8s.listSelected[ListResource[Pod]](requirementForName(f.version.processName)).futureValue.items
        pods.size shouldBe 3
        forAll(pods.head.spec.get.containers) { container =>
          container.resources shouldBe Some(
            skuber.Resource.Requirements(
              limits = Map("cpu" -> Quantity("800m"), "memory" -> Quantity("256Mi")),
              requests = Map("cpu" -> Quantity("800m"), "memory" -> Quantity("256Mi"))
            ))
          container.env should contain(EnvVar("ENV_VARIABLE", EnvVar.StringValue("VALUE")))
        }
      }
    }
  }

  test("should deploy scenario with custom logging configuration") {
    val f = createKafkaFixture()

    def withManager(manager: K8sDeploymentManager)(action: ProcessVersion => Unit): Unit = {
      val version = ProcessVersion(VersionId(11), ProcessName(f.scenario.id), ProcessId(1234), "testUser", Some(22))
      manager.deploy(version, DeploymentData.empty, f.scenario, None).futureValue

      action(version)
      cancelAndAssertCleanup(manager, version)
    }

    val customLogger = "test.passing.logback.conf"
    val logbackFile = {
      val tempFile = Files.createTempFile("test-logback", ".xml")
      Files.write(tempFile,
        s"""
           |<configuration scan="true" scanPeriod="5 seconds">
           |    <logger name="$customLogger" level="WARN"/>
           |</configuration>
           |""".stripMargin.getBytes)
      tempFile.toFile
    }
    val manager: K8sDeploymentManager = prepareManager(deployConfig =
      kafkaDeployConfig
        .withValue(
          "logbackConfigPath", fromAnyRef(logbackFile.toString)
        )
    )

    withManager(manager) { version =>
      eventually {
        val cm = k8s.listSelected[ListResource[ConfigMap]](requirementForName(version.processName)).futureValue.items.find {
          _.data.isDefinedAt("logback.xml")
        }.head
        cm.data("logback.xml").contains(customLogger) shouldBe true
      }
    }

    withManager(prepareManager()) { version =>
      eventually {
        val cm = k8s.listSelected[ListResource[ConfigMap]](requirementForName(version.processName)).futureValue.items.find {
          _.data.isDefinedAt("logback.xml")
        }.head
        cm.data("logback.xml").contains(customLogger) shouldBe false
      }
    }
  }

  test("should deploy scenarios with common logging conf") {
    val configMapName = "test" + new Random().nextInt(1000000)
    val f = createKafkaFixture(deployConfig = kafkaDeployConfig.withValue("commonConfigMapForLogback", fromAnyRef(configMapName)))

    f.withRunningScenario {
      //check if cm exists
      k8s.list[ListResource[ConfigMap]]().futureValue.items.exists(_.name == configMapName) shouldBe true

      //check if cm is actually mounted
      val pod = k8s.listSelected[ListResource[Pod]](requirementForName(f.version.processName)).futureValue.items.head
      pod.spec.get.volumes.exists(_.source match {
        case Volume.ConfigMapVolumeSource(`configMapName`, _, _, _) => true
        case _ => false
      }) shouldBe true
    }
    // check that after cancelling scenario CM is still there
    k8s.list[ListResource[ConfigMap]]().futureValue.items.exists(_.name == configMapName) shouldBe true

    //cleanup
    k8s.delete[ConfigMap](configMapName)
  }

  test("should deploy within specified resource quota") {
    val f = createKafkaFixture()
    k8s.create(Quota(metadata = ObjectMeta(name = "nu-pods-limit"), spec = Some(Quota.Spec(hard = Map[String, Quantity]("pods" -> Quantity("3")))))) //two pods takes test setup
    f.withRunningScenario(())
    k8s.delete[Resource.Quota]("nu-pods-limit").futureValue
  }

  test("should not deploy when resource quota exceeded") {
    val f = createKafkaFixture()
    k8s.create(Quota(metadata = ObjectMeta(name = "nu-pods-limit"), spec = Some(Quota.Spec(hard = Map[String, Quantity]("pods" -> Quantity("2")))))) //two pods takes test setup

    f.manager.validate(f.version, DeploymentData.empty, f.scenario).failed.futureValue shouldEqual
      ResourceQuotaExceededException("Cluster is full. Release some cluster resources.")

    cancelAndAssertCleanup(f.manager, f.version)
    k8s.delete[Resource.Quota]("nu-pods-limit").futureValue
  }

  test("should expose prometheus metrics") {
    val port = 8080
    val f = createKafkaFixture(deployConfig = kafkaDeployConfig
      .withValue("k8sDeploymentConfig.spec.template.spec.containers",
        fromIterable(List(
          fromMap(Map(
            "name" -> "runtime",
            "env" -> fromIterable(List(fromMap(Map(
              "name" -> "PROMETHEUS_METRICS_PORT",
              "value" -> s"$port"
            ).asJava
            )
            ).asJava),
            "ports" -> fromIterable(List(fromMap(Map(
              "name" -> "prometheus",
              "containerPort" -> port,
              "protocol" -> "TCP"
            ).asJava)
            ).asJava
            )
          ).asJava)
        ).asJava)
      ))

    f.withRunningScenario {
      val pod = k8s.listSelected[ListResource[Pod]](requirementForName(f.version.processName)).futureValue.items.head
      pod.spec.get.containers.head.ports should contain theSameElementsAs List(Port(port, name = "prometheus"))

      k8sTestUtils.withPortForwarded(pod, port) { localPort =>
        eventually {
          basicRequest.get(uri"http://localhost:$localPort").send().body.right.get.contains("jvm_memory_bytes_committed") shouldBe true
        }
      }
    }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    kafka.start()
  }

  override protected def cleanup(): Unit = {
    super.cleanup()
    kafka.stop()
  }

  private def cancelAndAssertCleanup(manager: K8sDeploymentManager, version: ProcessVersion) = {
    manager.cancel(version.processName, DeploymentData.systemUser).futureValue
    eventually {
      manager.findJobStatus(version.processName).futureValue shouldBe None
    }
    assertNoGarbageLeft()
  }

  private val kafkaDeployConfig: Config = baseDeployConfig("streaming")
    .withValue(KafkaConfigProperties.bootstrapServersProperty("configExecutionOverrides.modelConfig.kafka"), fromAnyRef(s"${KafkaK8sSupport.kafkaServiceName}:9092"))
    .withValue(KafkaConfigProperties.property("configExecutionOverrides.modelConfig.kafka", "schema.registry.url"), fromAnyRef(s"http://${KafkaK8sSupport.srServiceName}:8081"))
  private val modelData: LocalModelData = LocalModelData(ConfigFactory.empty
    //e.g. when we want to run Designer locally with some proxy?
    .withValue(KafkaConfigProperties.bootstrapServersProperty(), fromAnyRef("localhost:19092"))
    .withValue(KafkaConfigProperties.property("auto.offset.reset"), fromAnyRef("earliest"))
    .withValue("kafka.lowLevelComponentsEnabled", fromAnyRef(false))
    .withValue("exceptionHandlingConfig.topic", fromAnyRef("errors")), new EmptyProcessConfigCreator)

  private def prepareManager(modelData: LocalModelData = modelData, deployConfig: Config = kafkaDeployConfig): K8sDeploymentManager = {
    new K8sDeploymentManager(modelData, K8sDeploymentManagerConfig.parse(deployConfig))
  }

  val defaultSchema = """{"type":"object","properties":{"message":{"type":"string"}}}"""

  private def createKafkaFixture(modelData: LocalModelData = modelData, deployConfig: Config = kafkaDeployConfig, schema: String = defaultSchema) = {
    val seed = new Random().nextInt()
    val input = s"ping-$seed"
    val output = s"pong-$seed"

    kafka.createSchema(s"$input-value", schema)
    kafka.createSchema(s"$output-value", schema)

    val manager = prepareManager(modelData, deployConfig)
    val scenario = ScenarioBuilder
      .streamingLite("foo scenario \u2620")
      .source("source", "kafka", "Topic" -> s"'$input'", "Schema version" -> "'latest'")
      .emptySink("sink", "kafka", "Topic" -> s"'$output'", "Schema version" -> "'latest'", "Key" -> "", "Raw editor" -> "true", "Value validation mode" -> "'strict'",
        "Value" -> "#input")
    logger.info(s"Running kafka test on ${scenario.id} $input - $output")
    val version = ProcessVersion(VersionId(11), ProcessName(scenario.id), ProcessId(1234), "testUser", Some(22))
    new KafkaTestFixture(inputTopic = input, outputTopic = output, manager = manager, scenario = scenario, version = version)
  }

  private class KafkaTestFixture(val inputTopic: String,
                                 val outputTopic: String,
                                 manager: K8sDeploymentManager,
                                 scenario: CanonicalProcess,
                                 version: ProcessVersion) extends K8sDeploymentManagerTestFixture(manager, scenario, version) {
    override def withRunningScenario(action: => Unit): Unit = {
      kafka.createTopic(inputTopic)
      kafka.createTopic(outputTopic)
      super.withRunningScenario(action)
      //should not fail
      assertNoGarbageLeft()
    }
  }

}
