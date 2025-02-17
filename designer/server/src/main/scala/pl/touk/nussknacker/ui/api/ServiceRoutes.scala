package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpResponse, ResponseEntity}
import akka.http.scaladsl.server.{Directives, ExceptionHandler, Route}
import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.engine.ModelData
import pl.touk.nussknacker.engine.graph.evaluatedparam.Parameter
import pl.touk.nussknacker.engine.util.service.query.ServiceQuery
import pl.touk.nussknacker.ui.security.api.LoggedUser

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.{Encoder, Json}
import io.circe.generic.JsonCodec
import pl.touk.nussknacker.engine.util.json.BestEffortJsonEncoder
import pl.touk.nussknacker.engine.util.service.query.ServiceQuery.{QueryResult, ServiceNotFoundException}
import pl.touk.nussknacker.security.Permission
import pl.touk.nussknacker.ui.process.processingtypedata.ProcessingTypeDataProvider
import pl.touk.nussknacker.engine.util.Implicits.RichScalaMap

class ServiceRoutes(modelDataMap: ProcessingTypeDataProvider[ModelData])
                   (implicit ec: ExecutionContext)
  extends Directives
    with RouteWithUser
    with FailFastCirceSupport
    with LazyLogging{

  import ServiceRoutes._

  private implicit val encoder: Encoder[ServiceQuery.QueryResult] = {

    //FIXME: semi-auto like below does not work :/
    //implicit val queryResult: Encoder[QueryServiceResult] = io.circe.generic.semiauto.deriveConfiguredEncoder[QueryServiceResult]
    //io.circe.generic.semiauto.deriveConfiguredEncoder[ServiceQuery.QueryResult]
    new Encoder[QueryResult] {
      override def apply(a: QueryResult): Json = {
        val classLoader = if(a.result != null) a.result.getClass.getClassLoader else ClassLoader.getSystemClassLoader
        val resultEncoder: Encoder[Any] = BestEffortJsonEncoder(failOnUnkown = false, classLoader).circeEncoder
        Json.obj(
          "result" -> resultEncoder(a.result),
          "collectedResults" -> Json.fromValues(a.collectedResults.map(r => Json.obj("name" -> Json.fromString(r.name), "result" -> resultEncoder(r.result)))))
      }
    }
  }

  private implicit def serviceExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case e@ServiceNotFoundException(_) =>
        complete(Marshal(JsonThrowable(e)).to[ResponseEntity].map(res => HttpResponse(status = NotFound, entity = res)))
      case NonFatal(e) =>
        complete(Marshal(JsonThrowable(e)).to[ResponseEntity].map(res => HttpResponse(status = InternalServerError, entity = res)))
    }

  override def securedRoute(implicit user: LoggedUser): Route = {
      handleExceptions(serviceExceptionHandler) {
        invokeServicePath
      }
  }

  private def invokeServicePath(implicit user: LoggedUser) =
    path("service" / Segment / Segment) { (processingType, serviceName) =>
      post {
        val modelData = modelDataMap.forTypeUnsafe(processingType)
        authorize(canUserInvokeService(user, serviceName, modelData)) {
          entity(as[List[Parameter]]) { params =>
            complete {
              invokeService(serviceName, modelData, params)
            }
          }
        }
      }
    }

  private[api] def canUserInvokeService(user: LoggedUser, serviceName: String, modelData: ModelData): Boolean = {

    def hasUserDeployPermissionForCategories(categories: Option[List[String]]) = {
      def isAllowed(categoryName: String): Boolean = user.can(categoryName, Permission.Deploy)
      categories.forall(_.exists(isAllowed))
    }

    val servicesToCategories = modelData.processDefinition.services.mapValuesNow(_.categories)

    servicesToCategories.get(serviceName).forall(hasUserDeployPermissionForCategories)
  }

  private def invokeService(serviceName: String, modelData: ModelData, params: List[Parameter]): Future[QueryResult] = {
    new ServiceQuery(modelData).invoke(serviceName, params = params)
  }
}

object ServiceRoutes {
  @JsonCodec case class JsonThrowable(className: String, message: Option[String], stacktrace: List[String])

  object JsonThrowable {
    def apply(e: Throwable):JsonThrowable =
      JsonThrowable(
        className = e.getClass.getCanonicalName,
        message = Option(e.getMessage),
        stacktrace = e.getStackTrace.toList.map(_.toString)
      )
  }

}
