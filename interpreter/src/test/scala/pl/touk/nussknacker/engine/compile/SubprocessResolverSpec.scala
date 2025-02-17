package pl.touk.nussknacker.engine.compile

import cats.data.{NonEmptyList, ValidatedNel}
import cats.data.Validated.{Invalid, Valid}
import org.scalatest.Inside
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError
import pl.touk.nussknacker.engine.api.{FragmentSpecificData, MetaData}
import pl.touk.nussknacker.engine.build.GraphBuilder.Creator
import pl.touk.nussknacker.engine.build.{ScenarioBuilder, GraphBuilder}
import pl.touk.nussknacker.engine.canonicalgraph.{CanonicalProcess, canonicalnode}
import pl.touk.nussknacker.engine.canonicalgraph.canonicalnode.{FlatNode, Subprocess}
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError._
import pl.touk.nussknacker.engine.graph.evaluatedparam.Parameter
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.graph.node.SubprocessInputDefinition.{SubprocessClazzRef, SubprocessParameter}
import pl.touk.nussknacker.engine.graph.node._
import pl.touk.nussknacker.engine.graph.sink.SinkRef
import pl.touk.nussknacker.engine.graph.subprocess.SubprocessRef

class SubprocessResolverSpec extends AnyFunSuite with Matchers with Inside{

  import pl.touk.nussknacker.engine.spel.Implicits._

  test("resolve simple process") {

    val process = ScenarioBuilder.streaming("test")
      .source("source", "source1")
      .subprocessOneOut("sub", "subProcess1", "output", "fragmentResult", "ala" -> "'makota'")
      .subprocessOneOut("sub2", "subProcess1", "output", "fragmentResult", "ala" -> "'makota'")
      .emptySink("sink", "sink1")

    val suprocessParameters = List(SubprocessParameter("ala", SubprocessClazzRef[String]))

    val subprocess = CanonicalProcess(MetaData("subProcess1", FragmentSpecificData()),
      List(
        FlatNode(SubprocessInputDefinition("start", suprocessParameters)),
        canonicalnode.FilterNode(Filter("f1", "false"), List()), FlatNode(SubprocessOutputDefinition("out1", "output", List.empty))) , List.empty
    )

    val resolvedValidated = SubprocessResolver(Set(subprocess)).resolve(process)

    resolvedValidated shouldBe Symbol("valid")
    val resolved = resolvedValidated.toOption.get

    resolved.nodes.filter(_.isInstanceOf[Subprocess]) shouldBe Symbol("empty")
    resolved.nodes.find(_.id == "f1") shouldBe Symbol("empty")
    resolved.nodes.find(_.id == "sub-f1") shouldBe Symbol("defined")
    resolved.nodes.find(_.id == "sub").get.data should matchPattern { case SubprocessInput(_, _, _, _, Some(subprocessParameters)) => }
    resolved.nodes.find(_.id == "sub").get.data
    resolved.nodes.find(_.id == "sub2-f1") shouldBe Symbol("defined")
  }

  test("resolve nested fragments") {

    val process = ScenarioBuilder.streaming("test")
      .source("source", "source1")
      .subprocessOneOut("sub", "subProcess1", "output", "fragmentResult", "param" -> "'makota'")
      .emptySink("sink", "sink1")

    val subprocess = CanonicalProcess(MetaData("subProcess2", FragmentSpecificData()),
      List(
        FlatNode(SubprocessInputDefinition("start", List(SubprocessParameter("param", SubprocessClazzRef[String])))),
        canonicalnode.FilterNode(Filter("f1", "#param == 'a'"),
        List(FlatNode(Sink("deadEnd", SinkRef("sink1", List()))))
      ), FlatNode(SubprocessOutputDefinition("out1", "output", List.empty))), List.empty)

    val nested =  CanonicalProcess(MetaData("subProcess1", FragmentSpecificData()),
      List(
        FlatNode(SubprocessInputDefinition("start", List(SubprocessParameter("param", SubprocessClazzRef[String])))),
        canonicalnode.Subprocess(SubprocessInput("sub2",
        SubprocessRef("subProcess2", List(Parameter("param", "#param")))), Map("output" -> List(FlatNode(SubprocessOutputDefinition("sub2Out", "output", List.empty)))))), List.empty
    )

    val resolvedValidated = SubprocessResolver(Set(subprocess, nested)).resolve(process)


    resolvedValidated shouldBe Symbol("valid")
    val resolved = resolvedValidated.toOption.get

    resolved.nodes.filter(_.isInstanceOf[Subprocess]) shouldBe Symbol("empty")
    resolved.nodes.find(_.id == "f1") shouldBe Symbol("empty")
    resolved.nodes.find(_.id == "sub2") shouldBe Symbol("empty")
    resolved.nodes.find(_.id == "sub2-f1") shouldBe Symbol("empty")

    resolved.nodes.find(_.id == "sub") shouldBe Symbol("defined")
    resolved.nodes.find(_.id == "sub-sub2") shouldBe Symbol("defined")
    resolved.nodes.find(_.id == "sub-sub2-f1") shouldBe Symbol("defined")
  }

  test("not resolve fragment with missing parameters") {

    val process = ScenarioBuilder.streaming("test")
      .source("source", "source1")
      .subprocessOneOut("sub", "subProcess1", "output", "fragmentResult", "badala" -> "'makota'")
      .emptySink("sink", "sink1")

    val subprocess = CanonicalProcess(MetaData("subProcess1", FragmentSpecificData()),
      List(
        FlatNode(SubprocessInputDefinition("start", List(SubprocessParameter("param", SubprocessClazzRef[String])))),
        canonicalnode.FilterNode(Filter("f1", "false"), List()), FlatNode(SubprocessOutputDefinition("out1", "output", List.empty))), List.empty
    )

    val resolvedValidated = SubprocessResolver(Set(subprocess)).resolve(process)

    resolvedValidated shouldBe Invalid(NonEmptyList.of(RedundantParameters(Set("badala"), "sub"), MissingParameters(Set("param"), "sub")))

  }

  test("not resolve fragment with bad outputs") {

    val process = ScenarioBuilder.streaming("test")
      .source("source", "source1")
      .subprocessOneOut("sub", "subProcess1", "output", "fragmentResult", "ala" -> "'makota'")
      .emptySink("sink", "sink1")

    val subprocess = CanonicalProcess(MetaData("subProcess1", FragmentSpecificData()),
      List(
        FlatNode(SubprocessInputDefinition("start", List(SubprocessParameter("ala", SubprocessClazzRef[String])))),
        canonicalnode.FilterNode(Filter("f1", "false"), List()), FlatNode(SubprocessOutputDefinition("out1", "badoutput", List.empty))), List.empty
    )

    val resolvedValidated = SubprocessResolver(Set(subprocess)).resolve(process)

    resolvedValidated shouldBe Invalid(NonEmptyList.of(FragmentOutputNotDefined("badoutput", Set("sub-out1", "sub"))))

  }

  test("not disable fragment with many outputs") {

    val process = ScenarioBuilder.streaming("test")
      .source("source", "source1")
      .subprocessDisabledManyOutputs("sub", "subProcess1", List("ala" -> "'makota'"), Map(
        "output1" -> GraphBuilder.emptySink("sink1", "out1"),
        "output2" -> GraphBuilder.emptySink("sink2", "out2")
      ))

    val subprocess = CanonicalProcess(MetaData("subProcess1", FragmentSpecificData()),
      List(
        FlatNode(
          SubprocessInputDefinition("start",List(SubprocessParameter("ala", SubprocessClazzRef[String])))),
        canonicalnode.FilterNode(Filter("f1", "false"), List()),
        canonicalnode.SplitNode(
          Split("s"), List(
            List(FlatNode(SubprocessOutputDefinition("out1", "output", List.empty))),
            List(FlatNode(SubprocessOutputDefinition("out2", "output", List.empty)))
          )
        )
      ), List.empty
    )

    val resolvedValidated = SubprocessResolver(Set(subprocess)).resolve(process)

    resolvedValidated shouldBe Invalid(NonEmptyList.of(DisablingManyOutputsSubprocess("sub", Set("output1", "output2"))))

  }
  test("not disable fragment with no outputs") {

    val process = ScenarioBuilder.streaming("test")
      .source("source", "source1")
      .subprocessDisabledEnd("sub", "subProcess1")

    val subprocess = CanonicalProcess(MetaData("subProcess1", FragmentSpecificData()),
      List(
        FlatNode(
          SubprocessInputDefinition("start", List(SubprocessParameter("ala", SubprocessClazzRef[String])))),
        canonicalnode.FilterNode(Filter("f1", "false"), List()),
        FlatNode(Sink("disabledSubprocessMockedSink", SinkRef("disabledSubprocessMockedSink", List())))
      ), List.empty
    )

    val resolvedValidated = SubprocessResolver(Set(subprocess)).resolve(process)

    resolvedValidated shouldBe Invalid(NonEmptyList.of(DisablingNoOutputsSubprocess("sub")))

  }

  test("inline disabled fragment without inner nodes") {
    val processWithEmptySubprocess = ScenarioBuilder.streaming("test")
      .source("source", "source1")
      .subprocessOneOut("sub", "emptySubprocess", "output", "fragmentResult", "ala" -> "'makota'")
      .filter("d", "true")
      .emptySink("sink", "sink1")

    val processWithDisabledSubprocess =
      ScenarioBuilder.streaming("test")
        .source("source", "source1")
        .subprocessDisabled("sub", "subProcess1", "output", "ala" -> "'makota'")
        .filter("d", "true")
        .emptySink("sink", "sink1")

    val emptySubprocess = CanonicalProcess(MetaData("emptySubprocess", FragmentSpecificData()),
      List(
        FlatNode(
          SubprocessInputDefinition("start", List(SubprocessParameter("ala", SubprocessClazzRef[String])))),
        FlatNode(SubprocessOutputDefinition("out1", "output", List.empty))
      ), List.empty
    )
    val subprocess = CanonicalProcess(MetaData("subProcess1", FragmentSpecificData()),
      List(
        FlatNode(
          SubprocessInputDefinition("start", List(SubprocessParameter("ala", SubprocessClazzRef[String])))),
        canonicalnode.FilterNode(Filter("f1", "false"), List()),
        FlatNode(SubprocessOutputDefinition("out1", "output", List.empty))
      ), List.empty
    )
    val resolver = SubprocessResolver(Set(subprocess, emptySubprocess))
    val pattern: PartialFunction[ValidatedNel[ProcessCompilationError, CanonicalProcess], _] = {
      case Valid(CanonicalProcess(_, flatNodes, additional)) =>
        flatNodes(0) match {
          case FlatNode(Source(id, _, _)) =>
            id shouldBe "source"
          case e => fail(e.toString)
        }
        flatNodes(1) match {
          case FlatNode(SubprocessInput(id, _, _, _, _)) =>
            id shouldBe "sub"
          case e => fail(e.toString)
        }
        flatNodes(2) match {
          case FlatNode(SubprocessUsageOutput(_, _, _, _)) =>
            // output id is unpredictable
          case e => fail(e.toString)
        }
        flatNodes(3) match {
          case canonicalnode.FilterNode(Filter(id, _, _, _), _) =>
            id shouldBe "d"
          case e => fail(e.toString)
        }
        flatNodes(4) match {
          case FlatNode(node) => node.id shouldBe "sink"
          case e => fail(e.toString)
        }

    }
    inside(resolver.resolve(processWithEmptySubprocess))(pattern)
    inside(resolver.resolve(processWithDisabledSubprocess))(pattern)
  }

  test("resolve fragment at end of process") {
    val process = ScenarioBuilder.streaming("test")
      .source("source", "source1")
      .subprocessEnd("sub", "subProcess1", "ala" -> "'makota'")

    val subprocess = ScenarioBuilder
      .fragment("subProcess1", "ala" -> classOf[String])
      .filter("f1", "false")
      .emptySink("end", "sink1")

    val resolvedValidated = SubprocessResolver(Set(subprocess)).resolve(process)

    resolvedValidated shouldBe Symbol("valid")
    val resolved = resolvedValidated.toOption.get

    resolved.nodes.filter(_.isInstanceOf[Subprocess]) shouldBe Symbol("empty")
  }

  test("detect unknown fragment") {
    val process = ScenarioBuilder
      .streaming("process1")
      .source("id1", "source")
      .subprocessOneOut("nodeSubprocessId", "subProcessId", "fragmentResult", "output")
      .emptySink("id2", "sink")

    val resolvedValidated = SubprocessResolver(subprocesses = Set()).resolve(process)

    resolvedValidated shouldBe Invalid(NonEmptyList.of(UnknownSubprocess(id = "subProcessId", nodeId = "nodeSubprocessId")))
  }

  test("should resolve diamond fragments") {
    val process = ScenarioBuilder.streaming("test")
      .source("source", "source1")
      .subprocess("sub", "subProcess1", List("ala" -> "'makota'"), Map("output" -> "fragmentResult"), Map("output" ->
        GraphBuilder.emptySink("sink", "type")))

    val subprocess = CanonicalProcess(MetaData("subProcess1", FragmentSpecificData()),
      List(
        FlatNode(SubprocessInputDefinition("start", List(SubprocessParameter("ala", SubprocessClazzRef[String])))),
        canonicalnode.SplitNode(Split("split"),
          List(
            List(FlatNode(Filter("filter2a", "false")), FlatNode(BranchEndData(BranchEndDefinition("join2a", "join1")))),
            List(FlatNode(Filter("filter2b", "false")), FlatNode(BranchEndData(BranchEndDefinition("join2b", "join1"))))
          )
        )
      ), List(
        FlatNode(Join("join1", None, "union", Nil, Nil, None)),
        FlatNode(SubprocessOutputDefinition("output", "output", Nil, None))
      ):: Nil
    )

    val resolvedValidated = SubprocessResolver(Set(subprocess)).resolve(process).toOption.get.allStartNodes
    resolvedValidated should have length 2
  }

  test("handle subprocess with empty outputs") {
    val fragment = ScenarioBuilder
      .fragment("fragment1")
      .split("split",
        GraphBuilder.fragmentOutput("end1", "output1", "field" -> "false"),
        GraphBuilder.fragmentOutput("end2", "output2"),
      )
    val scenario = ScenarioBuilder
      .streaming("scenario1")
      .source("source", "source1")
      .subprocess("fragment", "fragment1", Nil, Map("output1" -> "outVar1"), Map(
        "output1" -> GraphBuilder.emptySink("id1", "sink"),
        "output2" -> GraphBuilder.emptySink("id2", "sink"),
      ))
    
    val resolvedValidated = SubprocessResolver(Set(fragment)).resolve(scenario)
    resolvedValidated shouldBe Symbol("valid")

  }

  test("detect multiple ends with same name") {
    val fragment = ScenarioBuilder
      .fragment("fragment1")
      .split("split",
        GraphBuilder.fragmentOutput("end1", "output1"),
        GraphBuilder.fragmentOutput("end2", "output1"),
      )
    val scenario = ScenarioBuilder
      .streaming("scenario1")
      .source("source", "source1")
      .subprocessOneOut("fragment", "fragment1", "output1", "outVar1")
      .emptySink("id1", "sink")

    val resolvedValidated = SubprocessResolver(Set(fragment)).resolve(scenario)
    resolvedValidated shouldBe Invalid(NonEmptyList.of(MultipleOutputsForName("output1", "fragment")))

  }

  //FIXME: not sure if it's good way.
  private implicit class DisabledSubprocess[R](builder: GraphBuilder[R]) extends GraphBuilder[R] {
    def subprocessDisabled(id: String, subProcessId: String, output: String, params: (String, Expression)*): GraphBuilder[R] =
      build(node => builder.creator(SubprocessNode(SubprocessInput(id, SubprocessRef(subProcessId, params.map(Parameter.tupled).toList), isDisabled = Some(true)), Map(output -> node))))

    def subprocessDisabledManyOutputs(id: String, subProcessId: String, params: List[(String, Expression)], outputs: Map[String, SubsequentNode]): R =
      creator(SubprocessNode(SubprocessInput(id, SubprocessRef(subProcessId, params.map(Parameter.tupled)), isDisabled = Some(true)), outputs))
    def subprocessDisabledEnd(id: String, subProcessId: String, params: (String, Expression)*): R =
      creator(SubprocessNode(SubprocessInput(id, SubprocessRef(subProcessId, params.map(Parameter.tupled).toList), isDisabled = Some(true)), Map()))
    override def build(inner: Creator[R]): GraphBuilder[R] = builder.build(inner)

    override def creator: Creator[R] = builder.creator
  }

}
