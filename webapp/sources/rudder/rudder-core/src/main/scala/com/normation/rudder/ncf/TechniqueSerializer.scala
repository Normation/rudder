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

package com.normation.rudder.ncf

import com.normation.cfclerk.domain.ReportingLogic
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.ncf
import com.normation.rudder.ncf.ParameterType.ParameterTypeService
import zio.*
import zio.json.*
import zio.json.ast.*

/*
 * Provides json serializer/deserializer for techniques
 */

class TechniqueSerializer(parameterTypeService: ParameterTypeService) {

  implicit val encoderParameterId:      JsonEncoder[ParameterId]      = JsonEncoder[String].contramap(_.value)
  implicit val encoderFieldParameterId: JsonFieldEncoder[ParameterId] = JsonFieldEncoder[String].contramap(_.value)
  implicit val encoderBundleName:       JsonEncoder[BundleName]       = JsonEncoder[String].contramap(_.value)
  implicit val encoderVersion:          JsonEncoder[Version]          = JsonEncoder[String].contramap(_.value)
  implicit val encoderReportingLogic:   JsonEncoder[ReportingLogic]   = JsonEncoder[String].contramap(_.value)
  implicit val encoderPolicyMode:       JsonEncoder[PolicyMode]       = JsonEncoder[String].contramap(_.name)

  implicit val encoderMethodElem:             JsonEncoder[MethodElem]                  = DeriveJsonEncoder.gen
  implicit val encoderTechniqueParameterType: JsonEncoder[ParameterType.ParameterType] =
    JsonEncoder[String].contramap(s => parameterTypeService.value(s).getOrElse(s.toString))
  implicit val encoderSelectOption:           JsonEncoder[SelectOption]                = DeriveJsonEncoder.gen
  implicit val encoderConstraints:            JsonEncoder[Constraints]                 = DeriveJsonEncoder.gen
  implicit val encoderTechniqueParameter:     JsonEncoder[TechniqueParameter]          = DeriveJsonEncoder.gen
  implicit val encoderResourceFileState:      JsonEncoder[ResourceFileState]           = JsonEncoder[String].contramap(_.value)
  implicit val encoderResourceFile:           JsonEncoder[ResourceFile]                = DeriveJsonEncoder.gen
  implicit val encoderTechnique:              JsonEncoder[EditorTechnique]             = DeriveJsonEncoder.gen

  implicit val decoderBundleName:         JsonDecoder[BundleName]                  = JsonDecoder[String].map(BundleName.apply)
  implicit val decoderParameterId:        JsonDecoder[ParameterId]                 = JsonDecoder[String].map(ParameterId.apply)
  implicit val decoderFieldParameterId:   JsonFieldDecoder[ParameterId]            = JsonFieldDecoder[String].map(ParameterId.apply)
  implicit val decoderVersion:            JsonDecoder[Version]                     = JsonDecoder[String].map(s => new Version(s))
  implicit val decoderReportingLogic:     JsonDecoder[ReportingLogic]              =
    JsonDecoder[String].mapOrFail(ReportingLogic.parse(_).left.map(_.msg))
  implicit val decoderParameterType:      JsonDecoder[ParameterType.ParameterType] =
    JsonDecoder[String].mapOrFail(parameterTypeService.create(_).left.map(_.msg))
  implicit val decoderPolicyMode:         JsonDecoder[PolicyMode]                  = JsonDecoder[String].mapOrFail(PolicyMode.parse(_) match {
    case Left(err) => Left(err.fullMsg)
    case Right(r)  => Right(r)
  })
  implicit val decoderSelectOption:       JsonDecoder[SelectOption]                = DeriveJsonDecoder.gen
  implicit val decoderConstraints:        JsonDecoder[Constraints]                 = DeriveJsonDecoder.gen
  implicit val decoderTechniqueParameter: JsonDecoder[TechniqueParameter]          = DeriveJsonDecoder.gen
  implicit val decoderMethodElem:         JsonDecoder[MethodElem]                  = DeriveJsonDecoder.gen
  implicit val decoderResourceFileState:  JsonDecoder[ResourceFileState]           =
    JsonDecoder[String].mapOrFail(ResourceFileState.parse(_).left.map(_.msg))
  implicit val decoderResourceFile:       JsonDecoder[ResourceFile]                = DeriveJsonDecoder.gen
  implicit val decoderTechnique:          JsonDecoder[EditorTechnique]             = DeriveJsonDecoder.gen

  def serializeTechniqueMetadata(technique: ncf.EditorTechnique): String = {
    technique.toJson
  }

  /*
   * The compilation output parameter is either just an error string or a CompilationOutput serialized to json.
   */
  def serializeEditorTechnique(editorTechnique: EditorTechnique, compilationOutput: Option[Json]): Either[String, Json] = {
    val output = compilationOutput match {
      case Some(o) => ("output", o) :: Nil
      case None    => Nil
    }

    editorTechnique.toJsonAST.map(_.merge(Json(("source", Json.Str("editor")) :: output*)))
  }

  def serializeMethodMetadata(method: GenericMethod): Json = {

    def serializeMethodParameter(param: MethodParameter): Json = {

      def serializeMethodConstraint(constraint: ncf.Constraint.Constraint): (String, Json) = {
        constraint match {
          case ncf.Constraint.AllowEmpty(allow)      => ("allow_empty_string", Json.Bool(allow))
          case ncf.Constraint.AllowWhiteSpace(allow) => ("allow_whitespace_string", Json.Bool(allow))
          case ncf.Constraint.MaxLength(max)         => ("max_length", Json.Num(max))
          case ncf.Constraint.MinLength(min)         => ("min_length", Json.Num(min))
          case ncf.Constraint.MatchRegex(re)         => ("regex", Json.Str(re))
          case ncf.Constraint.NotMatchRegex(re)      => ("not_regex", Json.Str(re))
          case ncf.Constraint.FromList(list)         => ("select", Json.Arr(list.map(Json.Str(_))*))
        }
      }

      val constraints = Json.Obj(param.constraint.map(serializeMethodConstraint)*)
      val paramType   = Json.Str(parameterTypeService.value(param.parameterType).getOrElse("Unknown"))
      Json.Obj(
        ("name"        -> Json.Str(param.id.value)),
        ("description" -> Json.Str(param.description)),
        ("constraints" -> constraints),
        ("type"        -> paramType)
      )
    }

    def serializeAgentSupport(agent: AgentType) = {
      agent match {
        case AgentType.Dsc          => Json.Str("dsc")
        case AgentType.CfeCommunity => Json.Str("cfengine-community")
      }
    }

    val parameters   = method.parameters.map(serializeMethodParameter)
    val agentSupport = method.agentSupport.map(serializeAgentSupport)
    val fields       = Chunk(
      ("id"            -> Json.Str(method.id.value)),
      ("name"          -> Json.Str(method.name)),
      ("description"   -> Json.Str(method.description)),
      ("condition"     -> Json
        .Obj(("prefix" -> Json.Str(method.classPrefix)), ("parameter" -> Json.Str(method.classParameter.value)))),
      ("agents"        -> Json.Arr(agentSupport*)),
      ("parameters"    -> Json.Arr(parameters*)),
      ("documentation" -> method.documentation.fold[Json](Json.Null)(Json.Str(_))),
      ("deprecated"    -> (method.deprecated match {
        case None       => Json.Null
        case Some(info) =>
          Json.Obj(("info" -> Json.Str(info)), ("replacedBy" -> method.renameTo.fold[Json](Json.Null)(Json.Str(_))))
      }))
    )
    Json.Obj(fields.filterNot(_._2 == Json.Null))
  }
}
