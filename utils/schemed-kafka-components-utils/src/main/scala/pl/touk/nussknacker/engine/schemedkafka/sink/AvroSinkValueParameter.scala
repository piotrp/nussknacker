package pl.touk.nussknacker.engine.schemedkafka.sink

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import org.apache.avro.Schema
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.CustomNodeError
import pl.touk.nussknacker.engine.api.definition.Parameter
import pl.touk.nussknacker.engine.schemedkafka.KafkaUniversalComponentTransformer.{SchemaVersionParamName, SinkKeyParamName, SinkValidationModeParameterName, SinkValueParamName, TopicParamName}
import pl.touk.nussknacker.engine.schemedkafka.AvroDefaultExpressionDeterminer
import pl.touk.nussknacker.engine.schemedkafka.typed.AvroSchemaTypeDefinitionExtractor
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.api.NodeId
import pl.touk.nussknacker.engine.util.sinkvalue.SinkValueData.{SinkRecordParameter, SinkSingleValueParameter, SinkValueParameter}

import scala.collection.immutable.ListMap

object AvroSinkValueParameter {
  import scala.jdk.CollectionConverters._

  type FieldName = String

  val restrictedParamNames: Set[FieldName] =
    Set(SchemaVersionParamName, SinkKeyParamName, SinkValidationModeParameterName, TopicParamName)

  /*
    We extract editor form from Avro schema
   */
  def apply(schema: Schema)(implicit nodeId: NodeId): ValidatedNel[ProcessCompilationError, SinkValueParameter] =
    toSinkValueParameter(schema, paramName = None, defaultValue = None)

  private def toSinkValueParameter(schema: Schema, paramName: Option[String], defaultValue: Option[Expression])
                                  (implicit nodeId: NodeId): ValidatedNel[ProcessCompilationError, SinkValueParameter] = {
    import cats.implicits.{catsStdInstancesForList, toTraverseOps}

    if (schema.getType == Schema.Type.RECORD) {
      val recordFields = schema.getFields.asScala.toList
      if (containsRestrictedNames(recordFields)) {
        /* TODO: Since GenericNodeTransformation#implementation passes all parameters in a single Map we need to restrict value parameter names,
         so they do not collide with other parameters like Topic or Key. */
        Invalid(NonEmptyList.one(
          CustomNodeError(nodeId.id, s"""Record field name is restricted. Restricted names are ${restrictedParamNames.mkString(", ")}""", None)))
      } else {
        val listOfValidatedParams = recordFields.map { recordField =>
          val fieldName = recordField.name()
          // Fields of nested records are flatten, e.g. { a -> { b -> _ } } => { a.b -> _ }
          val concatName = paramName.map(pn => s"$pn.$fieldName").getOrElse(fieldName)
          val sinkValueValidated = getDefaultValue(recordField, paramName).andThen { defaultValue =>
            toSinkValueParameter(schema = recordField.schema(), paramName = Some(concatName), defaultValue)
          }
          sinkValueValidated.map(sinkValueParam => fieldName -> sinkValueParam)
        }
        listOfValidatedParams.sequence.map(l => ListMap(l: _*)).map(SinkRecordParameter)
      }
    } else {
      Valid(AvroSinkSingleValueParameter(paramName, schema, defaultValue))
    }
  }

  private def getDefaultValue(fieldSchema: Schema.Field, paramName: Option[String])(implicit nodeId: NodeId): ValidatedNel[ProcessCompilationError, Option[Expression]] =
    new AvroDefaultExpressionDeterminer(handleNotSupported = true).determine(fieldSchema)
      .leftMap(_.map(err => CustomNodeError(err.getMessage, paramName)))

  private def containsRestrictedNames(fields: List[Schema.Field]): Boolean = {
    val fieldNames = fields.map(_.name()).toSet
    fieldNames.nonEmpty & (fieldNames & restrictedParamNames).nonEmpty
  }

}

object AvroSinkSingleValueParameter {

  def apply(paramName: Option[String], schema: Schema, defaultValue: Option[Expression]): SinkSingleValueParameter = {
    val typing = AvroSchemaTypeDefinitionExtractor.typeDefinition(schema)
    val name = paramName.getOrElse(SinkValueParamName)
    val parameter = (
      if (schema.isNullable) Parameter.optional(name, typing) else Parameter(name, typing)
      ).copy(
      isLazyParameter = true,
      defaultValue = defaultValue.map(_.expression)
    )
    SinkSingleValueParameter(parameter)
  }
}