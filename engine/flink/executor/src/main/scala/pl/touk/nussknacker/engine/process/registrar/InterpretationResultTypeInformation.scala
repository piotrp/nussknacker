package pl.touk.nussknacker.engine.process.registrar

import org.apache.flink.api.common.typeinfo.TypeInformation
import pl.touk.nussknacker.engine.InterpretationResult
import pl.touk.nussknacker.engine.api.PartReference
import pl.touk.nussknacker.engine.api.context.ValidationContext
import pl.touk.nussknacker.engine.flink.api.typeinformation.TypeInformationDetection
import pl.touk.nussknacker.engine.flink.typeinformation.ConcreteCaseClassTypeInfo
import pl.touk.nussknacker.engine.process.typeinformation.internal.InterpretationResultMapTypeInfo
import pl.touk.nussknacker.engine.util.Implicits.RichScalaMap

object InterpretationResultTypeInformation {

  def create(detection: TypeInformationDetection, validationContext: ValidationContext): TypeInformation[InterpretationResult] = {
    //TODO: here we still use Kryo :/
    val reference = TypeInformation.of(classOf[PartReference])
    val finalContext = detection.forContext(validationContext)

    ConcreteCaseClassTypeInfo[InterpretationResult](
      ("reference", reference),
      ("finalContext", finalContext)
    )
  }

  def create(detection: TypeInformationDetection, possibleContexts: Map[String, ValidationContext]): TypeInformation[InterpretationResult] = {
    InterpretationResultMapTypeInfo(possibleContexts.mapValuesNow(create(detection, _)))
  }
}
