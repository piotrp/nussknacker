package pl.touk.nussknacker.ui.process

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import pl.touk.nussknacker.engine.api.component.ComponentType.{ComponentType, Filter, FragmentInput, FragmentOutput, Fragments, Sink, Source, Switch, CustomNode => CustomNodeType}
import pl.touk.nussknacker.engine.api.component.{ComponentId, SingleComponentConfig}
import pl.touk.nussknacker.engine.api.deployment.ProcessActionType
import pl.touk.nussknacker.engine.api.process.VersionId
import pl.touk.nussknacker.engine.api.{FragmentSpecificData, MetaData}
import pl.touk.nussknacker.engine.build.{GraphBuilder, ScenarioBuilder}
import pl.touk.nussknacker.engine.canonicalgraph.canonicalnode.FlatNode
import pl.touk.nussknacker.engine.canonicalgraph.{CanonicalProcess, canonicalnode}
import pl.touk.nussknacker.engine.graph.node.SubprocessInputDefinition.{SubprocessClazzRef, SubprocessParameter}
import pl.touk.nussknacker.engine.graph.node.{Case, CustomNode, SubprocessInputDefinition, SubprocessOutputDefinition}
import pl.touk.nussknacker.restmodel.processdetails.ProcessAction
import pl.touk.nussknacker.ui.api.helpers.ProcessTestData._
import pl.touk.nussknacker.ui.api.helpers.TestProcessUtil._
import pl.touk.nussknacker.ui.api.helpers.TestProcessingTypes._
import pl.touk.nussknacker.ui.api.helpers.{TestCategories, TestProcessUtil, TestProcessingTypes}
import pl.touk.nussknacker.ui.component.DefaultComponentIdProvider
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter

import java.time.Instant

class ProcessObjectsFinderTest extends AnyFunSuite with Matchers with TableDrivenPropertyChecks {

  import pl.touk.nussknacker.engine.spel.Implicits._

  val subprocess = CanonicalProcess(MetaData("subProcess1", FragmentSpecificData()),
    List(
      canonicalnode.FlatNode(SubprocessInputDefinition("start", List(SubprocessParameter("ala", SubprocessClazzRef[String])))),
      canonicalnode.FlatNode(CustomNode("f1", None, otherExistingStreamTransformer2, List.empty)), FlatNode(SubprocessOutputDefinition("out1", "output", List.empty))), List.empty
  )

  val subprocessDetails = displayableToProcess(ProcessConverter.toDisplayable(subprocess, TestProcessingTypes.Streaming, TestCategories.Category1))

  private val process1 = displayableToProcess(TestProcessUtil.toDisplayable(
    ScenarioBuilder
      .streaming("fooProcess1")
      .source("source", existingSourceFactory)
      .customNode("custom", "out1", existingStreamTransformer)
      .customNode("custom2", "out2", otherExistingStreamTransformer)
      .emptySink("sink", existingSinkFactory)))

  private val process1deployed = process1.copy(lastAction = Option(ProcessAction(VersionId.initialVersionId, Instant.now(), "user", ProcessActionType.Deploy, Option.empty, Option.empty, Map.empty)))

  private val process2 = displayableToProcess(TestProcessUtil.toDisplayable(
    ScenarioBuilder
      .streaming("fooProcess2")
      .source("source", existingSourceFactory)
      .customNode("custom", "out1", otherExistingStreamTransformer)
      .emptySink("sink", existingSinkFactory)))

  private val process3 = displayableToProcess(TestProcessUtil.toDisplayable(
    ScenarioBuilder
      .streaming("fooProcess3")
      .source("source", existingSourceFactory)
      .emptySink("sink", existingSinkFactory)))

  private val process4 = displayableToProcess(TestProcessUtil.toDisplayable(
    ScenarioBuilder
      .streaming("fooProcess4")
      .source("source", existingSourceFactory)
      .subprocessOneOut("sub", "subProcess1", "output", "fragmentResult", "ala" -> "'makota'")
      .emptySink("sink", existingSinkFactory)))

  private val processWithSomeBasesStreaming = displayableToProcess(TestProcessUtil.toDisplayable(
    ScenarioBuilder
      .streaming("processWithSomeBasesStreaming")
      .source("source", existingSourceFactory)
      .filter("checkId", "#input.id != null")
      .filter("checkId2", "#input.id != null")
      .switch("switchStreaming", "#input.id != null", "output",
        Case("'1'", GraphBuilder.emptySink("out1", existingSinkFactory)),
        Case("'2'", GraphBuilder.emptySink("out2", existingSinkFactory2))
      )
  ))

  private val processWithSomeBasesFraud = displayableToProcess(TestProcessUtil.toDisplayable(
    ScenarioBuilder
      .streaming("processWithSomeBases")
      .source("source", existingSourceFactory)
      .filter("checkId", "#input.id != null")
      .switch("switchFraud", "#input.id != null", "output",
        Case("'1'", GraphBuilder.emptySink("out1", existingSinkFactory)),
        Case("'2'", GraphBuilder.emptySink("out2", existingSinkFactory2))
      ), TestProcessingTypes.Fraud
  ))

  private val processWithSubprocess = displayableToProcess(TestProcessUtil.toDisplayable(
    ScenarioBuilder
      .streaming("processWithSomeBases")
      .source("source", existingSourceFactory)
      .customNode("custom", "outCustom", otherExistingStreamTransformer2)
      .subprocess(subprocess.metaData.id, subprocess.metaData.id, Nil, Map.empty, Map(
        "sink" -> GraphBuilder.emptySink("sink", existingSinkFactory)
      ))
  ))

  private val defaultComponentIdProvider = new DefaultComponentIdProvider(Map(
    Streaming -> Map(
      otherExistingStreamTransformer -> SingleComponentConfig.zero.copy(componentId = Some(ComponentId(overriddenOtherExistingStreamTransformer)))
    ),
    Fraud -> Map(
      otherExistingStreamTransformer -> SingleComponentConfig.zero.copy(componentId = Some(ComponentId(overriddenOtherExistingStreamTransformer)))
    )
  ))

  test("should compute components usage count") {
    val table = Table(
      ("processes", "expectedData"),
      (List.empty, Map.empty),
      (List(process2, processWithSomeBasesStreaming), Map(
        sid(Sink, existingSinkFactory) -> 2, sid(Sink, existingSinkFactory2) -> 1, sid(Source, existingSourceFactory) -> 2,
        oid(overriddenOtherExistingStreamTransformer) -> 1, bid(Switch) -> 1, bid(Filter) -> 2
      )),
      (List(process2, subprocessDetails), Map(
        sid(Sink, existingSinkFactory) -> 1, sid(Source, existingSourceFactory) -> 1,
        oid(overriddenOtherExistingStreamTransformer) -> 1, sid(CustomNodeType, otherExistingStreamTransformer2) -> 1,
        bid(FragmentInput) -> 1,  bid(FragmentOutput) -> 1
      )),
      (List(process2, processWithSomeBasesStreaming, subprocessDetails), Map(
        sid(Sink, existingSinkFactory) -> 2, sid(Sink, existingSinkFactory2) -> 1, sid(Source, existingSourceFactory) -> 2,
        oid(overriddenOtherExistingStreamTransformer) -> 1, sid(CustomNodeType, otherExistingStreamTransformer2) -> 1,
        bid(Switch) -> 1, bid(Filter) -> 2, bid(FragmentInput) -> 1,  bid(FragmentOutput) -> 1
      )),
      (List(processWithSomeBasesFraud, processWithSomeBasesStreaming), Map(
        sid(Sink, existingSinkFactory) -> 1, sid(Sink, existingSinkFactory2) -> 1, sid(Source, existingSourceFactory) -> 1,
        fid(Sink, existingSinkFactory) -> 1, fid(Sink, existingSinkFactory2) -> 1, fid(Source, existingSourceFactory) -> 1,
        bid(Switch) -> 2, bid(Filter) -> 3
      )),
      (List(processWithSubprocess, subprocessDetails), Map(
        sid(Source, existingSourceFactory) -> 1, sid(Sink, existingSinkFactory) -> 1, sid(Fragments, subprocess.metaData.id) -> 1,
        sid(CustomNodeType, otherExistingStreamTransformer2) -> 2, bid(FragmentInput) -> 1,  bid(FragmentOutput) -> 1
      )),
      (List(subprocessDetails, subprocessDetails), Map(
        sid(CustomNodeType, otherExistingStreamTransformer2) -> 2, bid(FragmentInput) -> 2,  bid(FragmentOutput) -> 2
      ))
    )

    forAll(table) { (processes, expectedData) =>
      val result = ProcessObjectsFinder.computeComponentsUsageCount(defaultComponentIdProvider, processes)
      result shouldBe expectedData
    }
  }

  test("should compute components usage") {
    val table = Table(
      ("processes", "expected"),
      (List.empty, Map.empty),
      (List(process1deployed), Map(
        sid(Source, existingSourceFactory) -> List((process1deployed, List("source"))),
        sid(CustomNodeType, existingStreamTransformer) -> List((process1deployed, List("custom"))),
        oid(overriddenOtherExistingStreamTransformer) -> List((process1deployed, List("custom2"))),
        sid(Sink, existingSinkFactory) -> List((process1deployed, List("sink"))),
      )),
      (List(process1deployed, process2), Map(
        sid(Source, existingSourceFactory) -> List((process1deployed, List("source")), (process2, List("source"))),
        sid(CustomNodeType, existingStreamTransformer) -> List((process1deployed, List("custom"))),
        oid(overriddenOtherExistingStreamTransformer) -> List((process1deployed, List("custom2")), (process2, List("custom"))),
        sid(Sink, existingSinkFactory) -> List((process1deployed, List("sink")), (process2, List("sink"))),
      )),
      (List(processWithSomeBasesStreaming, processWithSomeBasesFraud), Map(
        sid(Source, existingSourceFactory) -> List((processWithSomeBasesStreaming, List("source"))),
        sid(Sink, existingSinkFactory) -> List((processWithSomeBasesStreaming, List("out1"))),
        sid(Sink, existingSinkFactory2) -> List((processWithSomeBasesStreaming, List("out2"))),
        bid(Filter) -> List((processWithSomeBasesFraud, List("checkId")), (processWithSomeBasesStreaming, List("checkId", "checkId2"))),
        bid(Switch) -> List((processWithSomeBasesFraud, List("switchFraud")), (processWithSomeBasesStreaming, List("switchStreaming"))),
        fid(Source, existingSourceFactory) -> List((processWithSomeBasesFraud, List("source"))),
        fid(Sink, existingSinkFactory) -> List((processWithSomeBasesFraud, List("out1"))),
        fid(Sink, existingSinkFactory2) -> List((processWithSomeBasesFraud, List("out2"))),
      )),
      (List(processWithSubprocess, subprocessDetails), Map(
        sid(Source, existingSourceFactory) -> List((processWithSubprocess, List("source"))),
        sid(CustomNodeType, otherExistingStreamTransformer2) -> List((processWithSubprocess, List("custom")), (subprocessDetails, List("f1"))),
        sid(Sink, existingSinkFactory) -> List((processWithSubprocess, List("sink"))),
        sid(Fragments, subprocess.metaData.id) -> List((processWithSubprocess, List(subprocess.metaData.id))),
        bid(FragmentInput) -> List((subprocessDetails, List("start"))),
        bid(FragmentOutput) -> List((subprocessDetails, List("out1"))),
      ))
    )

    forAll(table) { (process, expected) =>
      val result = ProcessObjectsFinder.computeComponentsUsage(defaultComponentIdProvider, process)
      result shouldBe expected
    }
  }

  private def sid(componentType: ComponentType, id: String) = ComponentId.default(Streaming, id, componentType)
  private def fid(componentType: ComponentType, id: String) = ComponentId.default(Fraud, id, componentType)
  private def bid(componentType: ComponentType) = ComponentId.forBaseComponent(componentType)
  private def oid(overriddenName: String) = ComponentId(overriddenName)
}
