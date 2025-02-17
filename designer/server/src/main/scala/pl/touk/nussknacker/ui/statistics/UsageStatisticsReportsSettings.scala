package pl.touk.nussknacker.ui.statistics

import io.circe.generic.JsonCodec
import pl.touk.nussknacker.engine.version.BuildInfo
import pl.touk.nussknacker.restmodel.process.ProcessingType
import pl.touk.nussknacker.ui.config.UsageStatisticsReportsConfig
import pl.touk.nussknacker.ui.process.processingtypedata.ProcessingTypeDataProvider

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.collection.immutable.ListMap
import scala.util.Random
import pl.touk.nussknacker.engine.util.Implicits.RichScalaMap

object UsageStatisticsReportsSettings {

  private val knownDeploymentManagerTypes = Set("flinkStreaming", "lite-k8s", "lite-embedded")

  private val streamingProcessingMode = "streaming"

  private val knownProcessingModes = Set(streamingProcessingMode, "request-response")

  // We aggregate custom deployment managers and processing modes as a "custom" to avoid leaking of internal, confidential data
  private val aggregateForCustomValues = "custom"

  def prepare(config: UsageStatisticsReportsConfig, processingTypeStatistics: ProcessingTypeDataProvider[ProcessingTypeUsageStatistics]): UsageStatisticsReportsSettings = {
    val queryParams = prepareQueryParams(config, processingTypeStatistics.all)
    val url = prepareUrl(queryParams)
    UsageStatisticsReportsSettings(config.enabled, url)
  }

  private[statistics] def prepareQueryParams(config: UsageStatisticsReportsConfig,
                                             processingTypeStatisticsMap: Map[ProcessingType, ProcessingTypeUsageStatistics]): ListMap[String, String] = {
    val deploymentManagerTypes = processingTypeStatisticsMap.values.map(_.deploymentManagerType).map {
      case dm if knownDeploymentManagerTypes.contains(dm) => dm
      case _ => aggregateForCustomValues
    }
    val dmParams = prepareValuesParams(deploymentManagerTypes, "dm")

    val processingModes = processingTypeStatisticsMap.values.map {
      case ProcessingTypeUsageStatistics(_, Some(mode)) if knownProcessingModes.contains(mode) => mode
      case ProcessingTypeUsageStatistics(deploymentManagerType, None) if deploymentManagerType.toLowerCase.contains(streamingProcessingMode) => streamingProcessingMode
      case _ => aggregateForCustomValues
    }
    val mParams = prepareValuesParams(processingModes, "m")

    ListMap(
      // We filter out blank fingerprint and source because when smb uses docker-compose, and forwards env variables eg. USAGE_REPORTS_FINGERPRINT
      // from system and the variable doesn't exist, there is no way to skip variable - it can be only set to empty
      "fingerprint" -> config.fingerprint.filterNot(_.isBlank).getOrElse(randomFingerprint),
      // If it is not set, we assume that it is some custom build from source code
      "source" -> config.source.filterNot(_.isBlank).getOrElse("sources"),
      "version" -> BuildInfo.version
    ) ++ dmParams ++ mParams
  }

  private def prepareValuesParams(values: Iterable[ProcessingType], metricCategoryKeyPart: String) = {
    val countsParams = values.groupBy(identity).mapValuesNow(_.size).map {
      case (value, count) =>
        s"${metricCategoryKeyPart}_$value" -> count.toString
    }.toList.sortBy(_._1)
    val singleParamValue = values.toSet.toList match {
      case Nil => "zero"
      case single :: Nil => single
      case _ => "multiple"
    }
    ListMap(countsParams: _*) + (s"single_$metricCategoryKeyPart" -> singleParamValue)
  }

  private[statistics] def prepareUrl(queryParams: ListMap[String, String]) = {
    queryParams.toList.map {
      case (k, v) => s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
    }.mkString("https://stats.nussknacker.io/?", "&", "")
  }

  private lazy val randomFingerprint = s"gen-${Random.alphanumeric.take(10).mkString}"

}

@JsonCodec case class UsageStatisticsReportsSettings(enabled: Boolean, url: String)