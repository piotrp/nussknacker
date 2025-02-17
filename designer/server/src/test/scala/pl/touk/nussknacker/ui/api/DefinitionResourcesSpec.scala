package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.model.{ContentTypeRange, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.CirceUtil.RichACursor
import pl.touk.nussknacker.engine.api.process.ProcessName
import pl.touk.nussknacker.test.{EitherValuesDetailedMessage, PatientScalaFutures}
import pl.touk.nussknacker.ui.api.helpers.TestCategories.TestCat
import pl.touk.nussknacker.ui.api.helpers.TestFactory.withPermissions
import pl.touk.nussknacker.ui.api.helpers.{EspItTest, ProcessTestData, SampleProcess, TestCategories, TestProcessingTypes}
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter

class DefinitionResourcesSpec extends AnyFunSpec with ScalatestRouteTest with FailFastCirceSupport
  with Matchers with PatientScalaFutures with EitherValuesDetailedMessage with BeforeAndAfterEach with BeforeAndAfterAll with EspItTest {

  private implicit final val string: FromEntityUnmarshaller[String] = Unmarshaller.stringUnmarshaller.forContentTypes(ContentTypeRange.*)

  private val definitionResources = new DefinitionResources(
    modelDataProvider = testModelDataProvider,
    processingTypeDataProvider = testProcessingTypeDataProvider,
    subprocessRepository,
    processCategoryService
  )

  it("should handle missing scenario type") {
    getProcessDefinitionData("foo") ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  it("should return definition data for existing scenario type") {
    getProcessDefinitionData(TestProcessingTypes.Streaming) ~> check {
      status shouldBe StatusCodes.OK

      val noneReturnType = responseAs[Json].hcursor
        .downField("processDefinition")
        .downField("customStreamTransformers")
        .downField("noneReturnTypeTransformer")

      noneReturnType.downField("returnType").focus shouldBe Some(Json.Null)
    }
  }

  it("should return definition data for allowed classes") {
    getProcessDefinitionData(TestProcessingTypes.Streaming) ~> check {
      status shouldBe StatusCodes.OK

      val typesInformation = responseAs[Json].hcursor
        .downField("processDefinition")
        .downField("typesInformation")
        .downAt(_.hcursor.downField("clazzName").get[String]("display").rightValue == "ReturningTestCaseClass")
        .downField("clazzName")
        .downField("display")

      typesInformation.focus.get shouldBe Json.fromString("ReturningTestCaseClass")
    }
  }


  it("should return all definition services") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json]
    }
  }

  it("should return info about raw editor based on annotation") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor = getParamEditor("simpleTypesService", "rawIntParam")

      editor shouldBe Json.obj("type" -> Json.fromString("RawParameterEditor"))
    }
  }

  it("should return info about simple editor based on annotation") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("simpleTypesService", "booleanParam")

      editor shouldBe Json.obj("type" -> Json.fromString("BoolParameterEditor"))
    }
  }

  it("should return info about dual editor based on annotation") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor = getParamEditor("simpleTypesService", "DualParam")

      editor shouldBe Json.obj(
        "simpleEditor" -> Json.obj("type" -> Json.fromString("StringParameterEditor")),
        "defaultMode" -> Json.fromString("SIMPLE"),
        "type" -> Json.fromString("DualParameterEditor")
      )
    }
  }

  it("should return info about editor based on config file") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("enricher", "param")

      editor shouldBe Json.obj("type" -> Json.fromString("StringParameterEditor"))
    }
  }

  it("should return info about editor based on fragment node configuration") {
    val processName = ProcessName(SampleProcess.process.id)
    val processWithSubProcess = ProcessTestData.validProcessWithSubprocess(processName)
    val displayableSubProcess = ProcessConverter.toDisplayable(processWithSubProcess.subprocess, TestProcessingTypes.Streaming, TestCategories.TestCat)
    saveSubProcess(displayableSubProcess)(succeed)
    saveProcess(processName, processWithSubProcess.process, TestCat)(succeed)

    getProcessDefinitionData(TestProcessingTypes.Streaming) ~> check {
      status shouldBe StatusCodes.OK

      val response = responseAs[Json].hcursor

      val editor = response
        .downField("processDefinition")
        .downField("subprocessInputs")
        .downField("sub1")
        .downField("parameters")
        .downAt(_.hcursor.get[String]("name").rightValue == "param1")
        .downField("editor")
        .focus.get

      editor shouldBe Json.obj("type" -> Json.fromString("StringParameterEditor"))
    }
  }

  it("should return info about editor based on dev config") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("multipleParamsService", "foo")

      editor shouldBe Json.obj(
        "type" -> Json.fromString("FixedValuesParameterEditor"),
        "possibleValues" -> Json.arr(
          Json.obj(
            "expression" -> Json.fromString("'test'"),
            "label" -> Json.fromString("test")
          )
        )
      )
    }
  }

  it("should override annotation config with dev config") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("multipleParamsService", "bar")

      editor shouldBe Json.obj("type" -> Json.fromString("StringParameterEditor"))
    }
  }

  it("should override dev config with config from file") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("multipleParamsService", "baz")

      editor shouldBe Json.obj(
        "type" -> Json.fromString("FixedValuesParameterEditor"),
        "possibleValues" -> Json.arr(
          Json.obj(
            "expression" -> Json.fromString("1"),
            "label" -> Json.fromString("1")
          ),
          Json.obj(
            "expression" -> Json.fromString("2"),
            "label" -> Json.fromString("2")
          )
        )
      )
    }
  }

  it("should return info about editor based on enum type") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("echoEnumService", "id")

      val cur = editor.hcursor
      cur.downField("type").as[String].rightValue shouldEqual "DualParameterEditor"
      cur.downField("defaultMode").as[String].rightValue shouldEqual "SIMPLE"

      val simpleEditorCur = cur.downField("simpleEditor")
      simpleEditorCur.downField("type").as[String].rightValue shouldEqual "FixedValuesParameterEditor"
      simpleEditorCur.downField("possibleValues").downN(0).downField("expression").as[String].rightValue shouldEqual "T(pl.touk.sample.JavaSampleEnum).FIRST_VALUE"
      simpleEditorCur.downField("possibleValues").downN(0).downField("label").as[String].rightValue shouldEqual "first_value"
      simpleEditorCur.downField("possibleValues").downN(1).downField("expression").as[String].rightValue shouldEqual "T(pl.touk.sample.JavaSampleEnum).SECOND_VALUE"
      simpleEditorCur.downField("possibleValues").downN(1).downField("label").as[String].rightValue shouldEqual "second_value"
    }
  }

  it("should return info about editor based on string type") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("multipleParamsService", "quax")

      editor shouldBe Json.obj(
        "simpleEditor" -> Json.obj("type" -> Json.fromString("StringParameterEditor")),
        "type" -> Json.fromString("DualParameterEditor"),
        "defaultMode" -> Json.fromString("RAW")
      )
    }
  }

  it("should return info about editor based on annotation for Duration param") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("datesTypesService", "durationParam")

      editor shouldBe Json.obj(
        "simpleEditor" -> Json.obj(
          "type" -> Json.fromString("DurationParameterEditor"),
          "timeRangeComponents" -> Json.arr(Json.fromString("DAYS"), Json.fromString("HOURS"))
        ),
        "type" -> Json.fromString("DualParameterEditor"),
        "defaultMode" -> Json.fromString("SIMPLE")
      )
    }
  }

  it("should return info about editor based on annotation for Period param") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("datesTypesService","periodParam" )

      editor shouldBe Json.obj(
        "simpleEditor" -> Json.obj(
          "type" -> Json.fromString("PeriodParameterEditor"),
          "timeRangeComponents" -> Json.arr(Json.fromString("YEARS"), Json.fromString("MONTHS"))
        ),
        "type" -> Json.fromString("DualParameterEditor"),
        "defaultMode" -> Json.fromString("SIMPLE")
      )
    }
  }

  it("should return info about editor based on annotation for Cron param") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val editor: Json = getParamEditor("datesTypesService","cronScheduleParam" )

      editor shouldBe Json.obj("type" -> Json.fromString("CronParameterEditor"))
    }
  }

  it("return mandatory value validator by default") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val validator: Json = getParamValidator("datesTypesService", "periodParam")


      validator shouldBe Json.arr(Json.obj("type" -> Json.fromString("MandatoryParameterValidator")))
    }
  }

  it("not return mandatory value validator for parameter marked with @Nullable") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val validator: Json = getParamValidator("optionalTypesService", "nullableParam")

      validator shouldBe Json.arr()
    }
  }

  it("override validator based on annotation with validator based on dev config") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val validator: Json = getParamValidator("optionalTypesService", "overriddenByDevConfigParam")

      validator shouldBe Json.arr(Json.obj("type" -> Json.fromString("MandatoryParameterValidator")))
    }
  }

  it("override validator based on dev config with validator based on file config") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val validator: Json = getParamValidator("optionalTypesService", "overriddenByFileConfigParam")
      val validatorForSimple: Json = getParamValidator("simpleTypesService", "booleanParam")

      validator shouldBe Json.arr()
    }
  }

  it("return info about validator based on param fixed value editor for node parameters") {
    getProcessDefinitionServices ~> check {
      status shouldBe StatusCodes.OK

      val validator: Json = getParamValidator("paramService", "param")

      validator shouldBe Json.arr(
        Json.obj("type" -> Json.fromString("MandatoryParameterValidator")),
        Json.obj(
          "possibleValues" -> Json.arr(
            Json.obj(
              "expression" -> Json.fromString("'a'"),
              "label" -> Json.fromString("a")
            ),
            Json.obj(
              "expression" -> Json.fromString("'b'"),
              "label" -> Json.fromString("b")
            ),
            Json.obj(
              "expression" -> Json.fromString("'c'"),
              "label" -> Json.fromString("c")
            )
          ),
          "type" -> Json.fromString("FixedValuesValidator")
        ))
    }
  }

  it("return info about validator based on param fixed value editor for additional properties") {
    getProcessDefinitionData(TestProcessingTypes.Streaming) ~> check {
      status shouldBe StatusCodes.OK

      val validators: Json = responseAs[Json].hcursor
        .downField("additionalPropertiesConfig")
        .downField("numberOfThreads")
        .downField("validators")
        .focus.get

      validators shouldBe
        Json.arr(
          Json.obj(
            "possibleValues" -> Json.arr(
              Json.obj(
                "expression" -> Json.fromString("1"),
                "label" -> Json.fromString("1")
              ),
              Json.obj(
                "expression" -> Json.fromString("2"),
                "label" -> Json.fromString("2")
              )
            ),
            "type" -> Json.fromString("FixedValuesValidator")
          )
        )
    }
  }

  it("return default value based on editor possible values") {
    getProcessDefinitionData(TestProcessingTypes.Streaming) ~> check {
      status shouldBe StatusCodes.OK

      val defaultExpression: Json = responseAs[Json].hcursor
        .downField("componentGroups")
        .downAt(_.hcursor.get[String]("name").rightValue == "enrichers")
        .downField("components")
        .downAt(_.hcursor.get[String]("label").rightValue == "echoEnumService")
        .downField("node")
        .downField("service")
        .downField("parameters")
        .downAt(_.hcursor.get[String]("name").rightValue == "id")
        .downField("expression")
        .downField("expression")
        .focus.get

      defaultExpression shouldBe Json.fromString("T(pl.touk.sample.JavaSampleEnum).FIRST_VALUE")
    }
  }

  // TODO: currently branch parameters must be determined on node template level - aren't enriched dynamically during node validation
  it("return branch parameters definition with standard parameters enrichments") {
    getProcessDefinitionData(TestProcessingTypes.Streaming) ~> check {
      status shouldBe StatusCodes.OK

      val responseJson = responseAs[Json]
      val defaultExpression: Json = responseJson.hcursor
        .downField("componentGroups")
        .downAt(_.hcursor.get[String]("name").rightValue == "base")
        .downField("components")
        .downAt(_.hcursor.get[String]("label").rightValue == "enrichWithAdditionalData")
        .downField("branchParametersTemplate")
        .downAt(_.hcursor.get[String]("name").rightValue == "role")
        .downField("expression")
        .downField("expression")
        .focus.get

      defaultExpression shouldBe Json.fromString("'Events'")
    }
  }

  private def getParamEditor(serviceName: String, paramName: String) = {
    responseAs[Json].hcursor
      .downField("streaming")
      .downField(serviceName)
      .downField("parameters")
      .downAt(_.hcursor.get[String]("name").rightValue == paramName)
      .downField("editor")
      .focus.get
  }

  private def getParamValidator(serviceName: String, paramName: String) = {
    responseAs[Json].hcursor
      .downField("streaming")
      .downField(serviceName)
      .downField("parameters")
      .downAt(_.hcursor.get[String]("name").rightValue == paramName)
      .downField("validators")
      .focus.get
  }

  private def getProcessDefinitionData(processingType: String): RouteTestResult = {
    Get(s"/processDefinitionData/$processingType?isSubprocess=false") ~> withPermissions(definitionResources, testPermissionRead)
  }

  private def getProcessDefinitionServices: RouteTestResult = {
    Get("/processDefinitionData/services") ~> withPermissions(definitionResources, testPermissionRead)
  }

}
