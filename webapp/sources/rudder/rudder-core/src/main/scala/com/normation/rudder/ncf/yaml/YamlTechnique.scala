/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.ncf.yaml

import com.normation.cfclerk.domain.LoadTechniqueError.Consistancy
import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.ReportingLogic.WeightedReport
import com.normation.errors.AccumulateErrors
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.inventory.domain.Version
import com.normation.rudder.ncf._
import com.normation.zio._
import zio.json._
import zio.json.yaml.DecoderYamlOps
import zio.yaml.YamlOps._

/*
 * Here we provide the datatype used to communicate with rudderc
 * Any changes here will have some impact on rudderc usage
 * and vice versa, any change in rudderc format should also be done here
 */

case class Technique(
    id:            BundleName,
    name:          String,
    version:       Version,
    description:   Option[String],
    items:         List[MethodItem],
    documentation: Option[String],
    category:      Option[String],
    tags:          Option[Map[String, String]],
    params:        Option[List[TechniqueParameter]]
)

case class MethodItem(
    // Common fields
    id:        String,
    name:      String,
    reporting: Option[Reporting],
    condition: Option[String],
    tags:      Option[Map[String, String]],
    // Call specific fields
    method:    Option[String],
    params:    Option[Map[ParameterId, String]],
    // Block specific fields
    items:     Option[List[MethodItem]]
)

case class Reporting(
    mode: String,
    id:   Option[String]
)

case class TechniqueParameter(
    id:          ParameterId,
    name:        ParameterId,
    description: Option[String],
    constraints: Constraints
)

case class Constraints(
    allow_empty: Option[Boolean]
)

class YamlTechniqueSerializer(resourceFileService: ResourceFileService) {
  implicit val encoderParameterId:        JsonEncoder[ParameterId]        = JsonEncoder[String].contramap(_.value)
  implicit val encoderFieldParameterId:   JsonFieldEncoder[ParameterId]   = JsonFieldEncoder[String].contramap(_.value)
  implicit val encoderBundleName:         JsonEncoder[BundleName]         = JsonEncoder[String].contramap(_.value)
  implicit val encoderVersion:            JsonEncoder[Version]            = JsonEncoder[String].contramap(_.value)
  implicit val encoderReporting:          JsonEncoder[Reporting]          = DeriveJsonEncoder.gen
  implicit val encoderConstraints:        JsonEncoder[Constraints]        = DeriveJsonEncoder.gen
  implicit lazy val encoderMethodElem:    JsonEncoder[MethodItem]         = DeriveJsonEncoder.gen
  implicit val encoderTechniqueParameter: JsonEncoder[TechniqueParameter] = DeriveJsonEncoder.gen
  implicit val encoderTechnique:          JsonEncoder[Technique]          = DeriveJsonEncoder.gen

  implicit val decoderBundleName:         JsonDecoder[BundleName]         = JsonDecoder[String].map(BundleName.apply)
  implicit val decoderParameterId:        JsonDecoder[ParameterId]        = JsonDecoder[String].map(ParameterId.apply)
  implicit val decoderFieldParameterId:   JsonFieldDecoder[ParameterId]   = JsonFieldDecoder[String].map(ParameterId.apply)
  implicit val decoderVersion:            JsonDecoder[Version]            = JsonDecoder[String].map(s => new Version(s))
  implicit val decoderReporting:          JsonDecoder[Reporting]          = DeriveJsonDecoder.gen
  implicit val decoderConstraints:        JsonDecoder[Constraints]        = DeriveJsonDecoder.gen
  implicit val decoderTechniqueParameter: JsonDecoder[TechniqueParameter] = DeriveJsonDecoder.gen
  implicit lazy val decoderMethodElem:    JsonDecoder[MethodItem]         = DeriveJsonDecoder.gen
  implicit val decoderTechnique:          JsonDecoder[Technique]          = DeriveJsonDecoder.gen
  implicit val decoder:                   JsonDecoder[EditorTechnique]    =
    JsonDecoder[Technique].mapOrFail(toJsonTechnique(_).either.runNow.left.map(_.msg))

  def toYml(technique: Technique): Either[String, String] = technique.toYaml()

  def toYml(technique: EditorTechnique): Either[String, String] = fromJsonTechnique(technique).toYaml()

  def fromYaml(yaml: String): Either[String, Technique] = yaml.fromYaml[Technique]

  private def toJsonTechnique(technique: Technique): IOResult[EditorTechnique] = {
    for {
      items     <- technique.items.accumulatePure(toMethodElem).toIO
      t          = EditorTechnique(
                     technique.id,
                     technique.version,
                     technique.name,
                     technique.category.getOrElse("ncf_techniques"),
                     items,
                     technique.description.getOrElse(""),
                     technique.documentation.getOrElse(""),
                     technique.params.getOrElse(Nil).map(toTechniqueParameter),
                     Seq(),
                     technique.tags.getOrElse(Map()),
                     None
                   )
      resources <- resourceFileService.getResources(t)
    } yield {
      t.copy(resources = resources)
    }
  }

  private def fromJsonTechnique(technique: EditorTechnique): Technique = {
    Technique(
      technique.id,
      technique.name,
      technique.version,
      if (technique.description.isEmpty) {
        None
      } else {
        Some(technique.description)
      },
      technique.calls.map(fromJson).toList,
      if (technique.documentation.isEmpty) None else Some(technique.documentation),
      Some(technique.category),
      if (technique.tags.isEmpty) None else Some(technique.tags),
      if (technique.parameters.isEmpty) {
        None
      } else {
        Some(technique.parameters.map(fromJson).toList)
      }
    )
  }

  def fromJson(techniqueParameter: com.normation.rudder.ncf.TechniqueParameter): TechniqueParameter = {
    TechniqueParameter(
      techniqueParameter.id,
      techniqueParameter.name,
      if (techniqueParameter.description.isEmpty) None else Some(techniqueParameter.description),
      Constraints(allow_empty = Some(techniqueParameter.mayBeEmpty))
    )
  }

  private def toTechniqueParameter(techniqueParameter: TechniqueParameter): com.normation.rudder.ncf.TechniqueParameter = {
    com.normation.rudder.ncf.TechniqueParameter(
      techniqueParameter.id,
      techniqueParameter.name,
      techniqueParameter.description.getOrElse(""),
      techniqueParameter.constraints.allow_empty.getOrElse(false)
    )
  }

  def toReporting(logic: ReportingLogic): Reporting  = {
    import ReportingLogic._
    logic match {
      case FocusReport(component)                                           => Reporting(FocusReport.key, Some(component))
      case WeightedReport | WorstReportWeightedOne | WorstReportWeightedSum => Reporting(logic.value, None)
    }
  }
  def fromJson(methodElem: MethodElem):   MethodItem = {
    methodElem match {
      case MethodBlock(id, name, reportingLogic, condition, items)               =>
        MethodItem(
          id,
          name,
          Some(toReporting(reportingLogic)),
          if (condition.isEmpty) None else Some(condition),
          None,
          None,
          None,
          Some(items.map(fromJson))
        )
      case MethodCall(method, id, parameters, condition, name, disableReporting) =>
        MethodItem(
          id,
          name,
          if (disableReporting) Some(Reporting("disabled", None)) else None,
          if (condition.isEmpty) None else Some(condition),
          None,
          Some(method.value),
          Some(parameters),
          None
        )
    }
  }

  def toReportingLogic(reporting: Reporting): PureResult[ReportingLogic] = {
    ReportingLogic.parse(reporting.mode ++ (reporting.id.map(":" ++ _).getOrElse("")))
  }
  private def toMethodElem(item: MethodItem): PureResult[MethodElem]     = {
    item.items match {
      case Some(items) =>
        for {
          reporting <- item.reporting.map(toReportingLogic).getOrElse(Right(WeightedReport))
          items     <- items.accumulatePure(toMethodElem)
        } yield {
          MethodBlock(
            item.id,
            item.name,
            reporting,
            item.condition.getOrElse(""),
            items
          )
        }
      case None        =>
        item.method match {
          case Some(method) =>
            Right(
              MethodCall(
                BundleName(method),
                item.id,
                item.params.getOrElse(Map()),
                item.condition.getOrElse(""),
                item.name,
                item.reporting == Some("disabled")
              )
            )
          case None         => Left(Consistancy("error"))

        }

    }
  }
}
