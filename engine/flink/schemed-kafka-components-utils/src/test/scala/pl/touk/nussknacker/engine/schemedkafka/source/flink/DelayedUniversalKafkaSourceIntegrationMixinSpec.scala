
package pl.touk.nussknacker.engine.schemedkafka.source.flink

import org.apache.avro.generic.GenericRecord
import org.scalatest.BeforeAndAfter
import pl.touk.nussknacker.engine.api.CustomStreamTransformer
import pl.touk.nussknacker.engine.api.process._
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.flink.test.RecordingExceptionConsumer
import pl.touk.nussknacker.engine.kafka.generic.FlinkKafkaDelayedSourceImplFactory
import pl.touk.nussknacker.engine.kafka.source.delayed.DelayedKafkaSourceFactory.{DelayParameterName, TimestampFieldParamName}
import pl.touk.nussknacker.engine.process.compiler.FlinkProcessCompiler
import pl.touk.nussknacker.engine.process.helpers.SampleNodes.SinkForLongs
import pl.touk.nussknacker.engine.process.registrar.FlinkProcessRegistrar
import pl.touk.nussknacker.engine.schemedkafka.KafkaAvroIntegrationMockSchemaRegistry.schemaRegistryMockClient
import pl.touk.nussknacker.engine.schemedkafka.KafkaAvroTestProcessConfigCreator
import pl.touk.nussknacker.engine.schemedkafka.KafkaUniversalComponentTransformer.{SchemaVersionParamName, TopicParamName}
import pl.touk.nussknacker.engine.schemedkafka.helpers.KafkaAvroSpecMixin
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.confluent.ConfluentSchemaBasedSerdeProvider
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.confluent.client.{ConfluentSchemaRegistryClientFactory, MockConfluentSchemaRegistryClientFactory, MockSchemaRegistryClient}
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.{SchemaBasedSerdeProvider, SchemaVersionOption}
import pl.touk.nussknacker.engine.schemedkafka.source.delayed.DelayedUniversalKafkaSourceFactory
import pl.touk.nussknacker.engine.spel
import pl.touk.nussknacker.engine.testing.LocalModelData

import java.time.Instant

trait DelayedUniversalKafkaSourceIntegrationMixinSpec extends KafkaAvroSpecMixin with BeforeAndAfter  {

  private lazy val creator: ProcessConfigCreator = new DelayedKafkaUniversalProcessConfigCreator {
    override protected def schemaRegistryClientFactory = new MockConfluentSchemaRegistryClientFactory(schemaRegistryMockClient)
  }

  override protected def schemaRegistryClient: MockSchemaRegistryClient = schemaRegistryMockClient

  override protected def confluentClientFactory: ConfluentSchemaRegistryClientFactory = new MockConfluentSchemaRegistryClientFactory(schemaRegistryMockClient)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val modelData = LocalModelData(config, creator)
    registrar = FlinkProcessRegistrar(new FlinkProcessCompiler(modelData), executionConfigPreparerChain(modelData))
  }

  before {
    SinkForLongs.clear()
  }

  protected def runAndVerify(topic: String, process: CanonicalProcess, givenObj: AnyRef): Unit = {
    kafkaClient.createTopic(topic, partitions = 1)
    pushMessage(givenObj, topic)
    run(process) {
      eventually {
        RecordingExceptionConsumer.dataFor(runId) shouldBe empty
        SinkForLongs.data should have size 1
      }
    }
  }

  protected def createProcessWithDelayedSource(topic: String, version: SchemaVersionOption, timestampField: String, delay: String): CanonicalProcess = {

    import spel.Implicits._

    ScenarioBuilder.streaming("kafka-universal-delayed-test")
      .parallelism(1)
      .source(
        "start",
        "kafka-universal-delayed",
        s"$TopicParamName" -> s"'${topic}'",
        s"$SchemaVersionParamName" -> asSpelExpression(formatVersionParam(version)),
        s"$TimestampFieldParamName" -> s"${timestampField}",
        s"$DelayParameterName" -> s"${delay}"
      )
      .emptySink("out", "sinkForLongs", "value" -> "T(java.time.Instant).now().toEpochMilli()")
  }

}

class DelayedKafkaUniversalProcessConfigCreator extends KafkaAvroTestProcessConfigCreator {

  override def sourceFactories(processObjectDependencies: ProcessObjectDependencies): Map[String, WithCategories[SourceFactory]] = {
    Map(
      "kafka-universal-delayed" -> defaultCategory(new DelayedUniversalKafkaSourceFactory[String, GenericRecord](schemaRegistryClientFactory, createSchemaBasedMessagesSerdeProvider,
        processObjectDependencies, new FlinkKafkaDelayedSourceImplFactory(None, UniversalTimestampFieldAssigner(_))))
    )
  }

  override def customStreamTransformers(processObjectDependencies: ProcessObjectDependencies): Map[String, WithCategories[CustomStreamTransformer]] =
    Map.empty

  override def sinkFactories(processObjectDependencies: ProcessObjectDependencies): Map[String, WithCategories[SinkFactory]] = {
    Map(
      "sinkForLongs" -> defaultCategory(SinkForLongs.toSinkFactory)
    )
  }

  override def expressionConfig(processObjectDependencies: ProcessObjectDependencies): ExpressionConfig = {
    super.expressionConfig(processObjectDependencies).copy(additionalClasses = List(classOf[Instant]))
  }

  override protected def createSchemaBasedMessagesSerdeProvider: SchemaBasedSerdeProvider = ConfluentSchemaBasedSerdeProvider.universal(schemaRegistryClientFactory)

}
