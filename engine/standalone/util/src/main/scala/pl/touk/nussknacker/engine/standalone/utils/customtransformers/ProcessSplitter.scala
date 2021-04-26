package pl.touk.nussknacker.engine.standalone.utils.customtransformers

import cats.data.NonEmptyList
import cats.data.Validated.Valid
import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import pl.touk.nussknacker.engine.api._
import pl.touk.nussknacker.engine.api.context.{ContextTransformation, ContextTransformationDef, JoinContextTransformation, ValidationContext}
import pl.touk.nussknacker.engine.api.exception.EspExceptionInfo
import pl.touk.nussknacker.engine.api.typed.typing.{SingleTypingResult, Typed, TypedObjectTypingResult, Unknown}
import pl.touk.nussknacker.engine.api.typed.{ReturningType, typing}
import pl.touk.nussknacker.engine.standalone.api.{JoinStandaloneCustomTransformer, StandaloneCustomTransformer}
import pl.touk.nussknacker.engine.standalone.api.types.InterpreterType

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

object ProcessSplitter extends CustomStreamTransformer {

  @MethodToInvoke(returnType = classOf[Object])
  def invoke(@ParamName("parts") parts: LazyParameter[java.util.Collection[Any]]): StandaloneCustomTransformer = {
    new ProcessSplitter(parts)
  }

}

class ProcessSplitter(parts: LazyParameter[java.util.Collection[Any]])
  extends StandaloneCustomTransformer with ReturningType {

  override def createTransformation(outputVariable: Option[String]): StandaloneCustomTransformation =
    (continuation: InterpreterType, lpi: LazyParameterInterpreter) => {
      val interpreter = lpi.createInterpreter(parts)
      (ctxs, ec) => {
        implicit val ecc: ExecutionContext = ec
        val all = Future.sequence(ctxs.map { ctx =>
          interpreter(ec, ctx).map { partsToRun =>
            partsToRun.asScala.toList.map { partToRun =>
              ctx.withVariable(outputVariable.get, partToRun)
            }
          }
        }).map(_.flatten)

        all.flatMap { partsToInterpret =>
          continuation(partsToInterpret, ecc)
        }
      }

    }

  override def returnType: typing.TypingResult = {
    parts.returnType match {
      case tc: SingleTypingResult if tc.objType.canBeSubclassOf(Typed[java.util.Collection[_]]) && tc.objType.params.nonEmpty =>
        tc.objType.params.head
      case _ => Unknown
    }
  }
}