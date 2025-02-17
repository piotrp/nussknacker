package pl.touk.nussknacker.engine.schemedkafka.source.flink

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory.fromAnyRef
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json._
import org.apache.kafka.common.record.TimestampType
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.test.{ScenarioTestData, ScenarioTestRecord}
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.flink.test.FlinkTestConfiguration
import pl.touk.nussknacker.engine.kafka.source.{InputMeta, InputMetaToJson}
import pl.touk.nussknacker.engine.process.runner.FlinkTestMain
import pl.touk.nussknacker.engine.schemedkafka.KafkaAvroIntegrationMockSchemaRegistry.schemaRegistryMockClient
import pl.touk.nussknacker.engine.schemedkafka.KafkaAvroTestProcessConfigCreator
import pl.touk.nussknacker.engine.schemedkafka.KafkaUniversalComponentTransformer.{SchemaVersionParamName, TopicParamName}
import pl.touk.nussknacker.engine.schemedkafka.schema.Address
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.SchemaVersionOption
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.confluent.ConfluentUtils
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.confluent.client.MockConfluentSchemaRegistryClientFactory
import pl.touk.nussknacker.engine.spel.Implicits._
import pl.touk.nussknacker.engine.testing.LocalModelData
import pl.touk.nussknacker.engine.testmode.TestProcess._
import pl.touk.nussknacker.engine.util.ThreadUtils
import pl.touk.nussknacker.engine.util.json.BestEffortJsonEncoder
import pl.touk.nussknacker.test.KafkaConfigProperties

import java.util.Collections

class TestFromFileSpec extends AnyFunSuite with Matchers with LazyLogging {

  private lazy val creator: KafkaAvroTestProcessConfigCreator = new KafkaAvroTestProcessConfigCreator {
    override protected def schemaRegistryClientFactory = new MockConfluentSchemaRegistryClientFactory(schemaRegistryMockClient)
  }

  private lazy val config = ConfigFactory.empty()
    .withValue(KafkaConfigProperties.bootstrapServersProperty(), fromAnyRef("notused:1111"))
    .withValue(KafkaConfigProperties.property("schema.registry.url"), fromAnyRef("notused:2222"))
    .withValue("kafka.avroKryoGenericRecordSchemaIdSerialization", fromAnyRef(false))

  test("Should pass correct timestamp from test data") {

    val topic = "simple"
    val expectedTimestamp = System.currentTimeMillis()
    val inputMeta = InputMeta(null, topic, 0, 1, expectedTimestamp, TimestampType.CREATE_TIME, Collections.emptyMap(), 0)
    val id: Int = registerSchema(topic)

    val process = ScenarioBuilder.streaming("test")
      .source(
        "start", "kafka", TopicParamName -> s"'$topic'", SchemaVersionParamName -> s"'${SchemaVersionOption.LatestOptionName}'"
      ).customNode("transform", "extractedTimestamp", "extractAndTransformTimestamp", "timestampToSet" -> "0L")
      .emptySink("end", "sinkForInputMeta", "value" -> "#inputMeta")

    val consumerRecord = new InputMetaToJson()
      .encoder(BestEffortJsonEncoder.defaultForTests.encode).apply(inputMeta)
      .mapObject(_.add("key", Null)
      .add("value", obj("city" -> fromString("Lublin"), "street" -> fromString("Lipowa"))))
    val testRecordJson = obj("keySchemaId" -> Null, "valueSchemaId" -> fromInt(id), "consumerRecord" -> consumerRecord)
    val scenarioTestData = ScenarioTestData(ScenarioTestRecord("start", testRecordJson) :: Nil)

    val results = run(process, scenarioTestData)

    val testResultVars = results.nodeResults("end").head.context.variables
    testResultVars.get("extractedTimestamp") shouldBe Some(expectedTimestamp)
    testResultVars.get("inputMeta") shouldBe Some(inputMeta)
  }

  private def registerSchema(topic: String) = {
    val subject = ConfluentUtils.topicSubject(topic, isKey = false)
    val parsedSchema = ConfluentUtils.convertToAvroSchema(Address.schema)
    schemaRegistryMockClient.register(subject, parsedSchema)
  }

  private def run(process: CanonicalProcess, scenarioTestData: ScenarioTestData): TestResults[Any] = {
    ThreadUtils.withThisAsContextClassLoader(getClass.getClassLoader) {
      FlinkTestMain.run(LocalModelData(config, creator), process, scenarioTestData,
        FlinkTestConfiguration.configuration(), identity
      )
    }
  }

}
