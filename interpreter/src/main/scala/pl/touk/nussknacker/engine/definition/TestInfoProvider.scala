package pl.touk.nussknacker.engine.definition

import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.JsonCodec
import pl.touk.nussknacker.engine.ModelData
import pl.touk.nussknacker.engine.api.process.{ComponentUseCase, SourceTestSupport, TestDataGenerator}
import pl.touk.nussknacker.engine.api.test.{ScenarioTestData, ScenarioTestRecord}
import pl.touk.nussknacker.engine.api.{MetaData, NodeId, process}
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.compile.ExpressionCompiler
import pl.touk.nussknacker.engine.compile.nodecompilation.NodeCompiler
import pl.touk.nussknacker.engine.graph.node.{Source, asSource}
import pl.touk.nussknacker.engine.resultcollector.ProductionServiceInvocationCollector
import pl.touk.nussknacker.engine.spel.SpelExpressionParser
import shapeless.syntax.typeable._


trait TestInfoProvider {

  def getTestingCapabilities(scenario: CanonicalProcess): TestingCapabilities

  def generateTestData(scenario: CanonicalProcess, size: Int): Option[ScenarioTestData]

}

@JsonCodec case class TestingCapabilities(canBeTested: Boolean, canGenerateTestData: Boolean)

object TestingCapabilities {
  val Disabled: TestingCapabilities = TestingCapabilities(canBeTested = false, canGenerateTestData = false)
}

class ModelDataTestInfoProvider(modelData: ModelData) extends TestInfoProvider with LazyLogging {

  private lazy val expressionCompiler = ExpressionCompiler.withoutOptimization(modelData).withExpressionParsers {
    case spel: SpelExpressionParser => spel.typingDictLabels
  }

  private lazy val nodeCompiler = new NodeCompiler(modelData.processWithObjectsDefinition, expressionCompiler, modelData.modelClassLoader.classLoader, ProductionServiceInvocationCollector, ComponentUseCase.TestDataGeneration)

  // TODO multiple-sources-test: handle multiple sources
  override def getTestingCapabilities(scenario: CanonicalProcess): TestingCapabilities = modelData.withThisAsContextClassLoader {
    val testingCapabilities = for {
      source <- findFirstSource(scenario)
      sourceObj <- prepareSourceObj(source)(scenario.metaData)
      canTest = sourceObj.isInstanceOf[SourceTestSupport[_]]
      canGenerateData = sourceObj.isInstanceOf[TestDataGenerator]
    } yield TestingCapabilities(canBeTested = canTest, canGenerateTestData = canGenerateData)
    testingCapabilities.getOrElse(TestingCapabilities.Disabled)
  }

  // TODO multiple-sources-test: handle multiple sources
  override def generateTestData(scenario: CanonicalProcess, size: Int): Option[ScenarioTestData] = {
    for {
      source <- findFirstSource(scenario)
      sourceObj <- prepareSourceObj(source)(scenario.metaData)
      testDataGenerator <- sourceObj.cast[TestDataGenerator]
      testData = testDataGenerator.generateTestData(size)
      scenarioTestRecords = testData.testRecords.map(record => ScenarioTestRecord(source.id, record.json, record.timestamp))
    } yield ScenarioTestData(scenarioTestRecords)
  }

  private def findFirstSource(scenario: CanonicalProcess): Option[Source] = {
    scenario.collectAllNodes.flatMap(asSource).headOption
  }

  private def prepareSourceObj(source: Source)(implicit metaData: MetaData): Option[process.Source] = {
    implicit val nodeId: NodeId = NodeId(source.id)
    nodeCompiler.compileSource(source).compiledObject.toOption
  }

}
