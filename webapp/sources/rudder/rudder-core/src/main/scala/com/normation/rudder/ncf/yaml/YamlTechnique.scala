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
import com.normation.errors._
import com.normation.errors.AccumulateErrors
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.inventory.domain.Version
import com.normation.rudder.ncf._
import zio.json._
import zio.json.yaml._

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
    documentation: Option[String],
    tags:          Option[Map[String, String]],
    category:      Option[String],
    params:        Option[List[TechniqueParameter]],
    items:         List[MethodItem]
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
    id:            ParameterId,
    name:          String,
    description:   String,
    documentation: Option[String],
    constraints:   Constraints
)

case class Constraints(
    allow_empty: Option[Boolean]
)

object YamlTechniqueSerializer {
  implicit val encoderParameterId:        JsonEncoder[ParameterId]        = JsonEncoder[String].contramap(_.value)
  implicit val encoderFieldParameterId:   JsonFieldEncoder[ParameterId]   = JsonFieldEncoder[String].contramap(_.value)
  implicit val encoderBundleName:         JsonEncoder[BundleName]         = JsonEncoder[String].contramap(_.value)
  implicit val encoderVersion:            JsonEncoder[Version]            = JsonEncoder[String].contramap(_.value)
  implicit val encoderReporting:          JsonEncoder[Reporting]          = DeriveJsonEncoder.gen
  implicit val encoderConstraints:        JsonEncoder[Constraints]        = DeriveJsonEncoder.gen
  implicit lazy val encoderMethodElem:    JsonEncoder[MethodItem]         = DeriveJsonEncoder.gen
  implicit val encoderTechniqueParameter: JsonEncoder[TechniqueParameter] = DeriveJsonEncoder.gen
  implicit val encoderTechnique:          JsonEncoder[Technique]          = DeriveJsonEncoder.gen
  implicit val encoderEditorTechnique:    JsonEncoder[EditorTechnique]    = JsonEncoder[Technique].contramap(fromEditorTechnique(_))

  implicit val decoderBundleName:         JsonDecoder[BundleName]         = JsonDecoder[String].map(BundleName.apply)
  implicit val decoderParameterId:        JsonDecoder[ParameterId]        = JsonDecoder[String].map(ParameterId.apply)
  implicit val decoderFieldParameterId:   JsonFieldDecoder[ParameterId]   = JsonFieldDecoder[String].map(ParameterId.apply)
  implicit val decoderVersion:            JsonDecoder[Version]            = JsonDecoder[String].map(s => new Version(s))
  implicit val decoderReporting:          JsonDecoder[Reporting]          = DeriveJsonDecoder.gen
  implicit val decoderConstraints:        JsonDecoder[Constraints]        = DeriveJsonDecoder.gen
  implicit val decoderTechniqueParameter: JsonDecoder[TechniqueParameter] = DeriveJsonDecoder.gen
  implicit lazy val decoderMethodElem:    JsonDecoder[MethodItem]         = DeriveJsonDecoder.gen
  implicit val decoderTechnique:          JsonDecoder[Technique]          = DeriveJsonDecoder.gen

  // be careful, the decoder derives values from yaml which does not know about Technique Resource - see companion class
  implicit val decoderEditorTechnique: JsonDecoder[EditorTechnique] = JsonDecoder[Technique].mapOrFail(toEditorTechnique(_))

  // ell the following methods are utilities for `decoderEditorTechnique`

  private def toEditorTechnique(technique: Technique): Either[String, EditorTechnique] = {
    (for {
      items <- technique.items.accumulatePure(toMethodElem)
    } yield {
      EditorTechnique(
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
    }).left.map(acc => acc.fullMsg)
  }

  private def toMethodElem(item: MethodItem): PureResult[MethodElem] = {
    def toReportingLogic(reporting: Reporting): PureResult[ReportingLogic] = {
      ReportingLogic.parse(reporting.mode ++ (reporting.id.map(":" ++ _).getOrElse("")))
    }

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
                // boolean for "disableReporting"
                item.reporting.map(_.mode == "disabled").getOrElse(false)
              )
            )
          case None         => Left(Consistancy("error"))

        }

    }
  }

  private def fromEditorTechnique(technique: EditorTechnique): Technique = {
    Technique(
      technique.id,
      technique.name,
      technique.version,
      if (technique.description.isEmpty) {
        None
      } else {
        Some(technique.description)
      },
      if (technique.documentation.isEmpty) None else Some(technique.documentation),
      if (technique.tags.isEmpty) None else Some(technique.tags),
      Some(technique.category),
      if (technique.parameters.isEmpty) {
        None
      } else {
        Some(technique.parameters.map(fromJsonParam).toList)
      },
      technique.calls.map(fromJsonMethodElem).toList
    )
  }

  private def fromJsonParam(techniqueParameter: com.normation.rudder.ncf.TechniqueParameter): TechniqueParameter = {
    TechniqueParameter(
      techniqueParameter.id,
      techniqueParameter.name,
      techniqueParameter.description,
      if (techniqueParameter.documentation.isEmpty) None else Some(techniqueParameter.documentation),
      Constraints(allow_empty = Some(techniqueParameter.mayBeEmpty))
    )
  }

  private def toTechniqueParameter(techniqueParameter: TechniqueParameter): com.normation.rudder.ncf.TechniqueParameter = {
    com.normation.rudder.ncf.TechniqueParameter(
      techniqueParameter.id,
      techniqueParameter.name,
      techniqueParameter.description,
      techniqueParameter.documentation.getOrElse(""),
      techniqueParameter.constraints.allow_empty.getOrElse(false)
    )
  }

  private def fromJsonMethodElem(methodElem: MethodElem): MethodItem = {
    def toReporting(logic: ReportingLogic): Reporting = {
      import ReportingLogic._
      logic match {
        case FocusReport(component)                                           =>
          Reporting(
            FocusReport.key,
            Some(component)
          )
        case WeightedReport | WorstReportWeightedOne | WorstReportWeightedSum => Reporting(logic.value, None)
      }
    }

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
          Some(items.map(fromJsonMethodElem))
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
}

class YamlTechniqueSerializer(resourceFileService: ResourceFileService) {
  import YamlTechniqueSerializer._

  // shortcut to avoid having to import yamlOps / toIO, but can be avoided
  def toYml(technique: Technique): IOResult[String] = technique.toYaml().toIO

  def toYml(technique: EditorTechnique): Either[String, String] = technique.toYaml()

  def fromYml(yaml: String): Either[String, Technique] = yaml.fromYaml[Technique]

  def yamlToEditorTechnique(yaml: String): IOResult[EditorTechnique] = {
    for {
      t         <- yaml.fromYaml[EditorTechnique].left.map(Inconsistency(_)).toIO
      resources <- resourceFileService.getResources(t)
    } yield t.copy(resources = resources)
  }

}
