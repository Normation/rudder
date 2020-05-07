/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

import java.util.regex.Pattern

import com.normation.errors.PureResult
import com.normation.errors.Unexpected
import cats.data.NonEmptyList
import com.normation.inventory.domain.Version
import com.normation.inventory.domain.AgentType
import com.normation.rudder.ncf
import com.normation.rudder.ncf.Constraint.Constraint
import com.normation.rudder.ncf.Constraint.CheckResult
import com.normation.rudder.ncf.ParameterType.ParameterTypeService
import net.liftweb.json.JField
import net.liftweb.json.JObject
import net.liftweb.json.JString
import net.liftweb.json.JValue

sealed trait NcfId {
  def value : String
  def validDscName : String
  def canonify : String = value.replaceAll("[^a-zA-Z0-9_]","_")
}
final case class BundleName (value : String) extends NcfId {
  val validDscName : String = value.split("_").map(_.capitalize).mkString("-")
}

final case class ParameterId (value : String) extends NcfId  {
  val validDscName : String = value.split("_").map(_.capitalize).mkString("")
}

sealed trait ResourceFileState {
  def value : String
}
object  ResourceFileState {
  case object New       extends ResourceFileState { val value ="new" }
  case object Deleted   extends ResourceFileState { val value ="deleted" }
  case object Modified  extends ResourceFileState { val value ="modified" }
  case object Untouched extends ResourceFileState { val value ="untouched" }

  val allValues = New :: Deleted :: Modified :: Untouched :: Nil

  def parse : String => PureResult[ResourceFileState] = {
    value =>
      allValues.find(_.value == value) match {
        case None => Left(Unexpected(s"$value is not valid resource state value"))
        case Some(state) => Right(state)
      }
  }
}

case class ResourceFile(
    path  : String
  , state : ResourceFileState
)

final case class Technique(
    bundleName  : BundleName
  , name        : String
  , category    : String
  , methodCalls : Seq[MethodCall]
  , version     : Version
  , description : String
  , parameters  : Seq[TechniqueParameter]
  , ressources  : Seq[ResourceFile]
)

final case class MethodCall(
    methodId   : BundleName
  , parameters : List[(ParameterId,String)]
  , condition  : String
  , component  : String
)

final case class GenericMethod(
    id             : BundleName
  , name           : String
  , parameters     : Seq[MethodParameter]
  , classParameter : ParameterId
  , classPrefix    : String
  , agentSupport   : Seq[AgentType]
  , description    : String
  , documentation  : Option[String]
)

final case class MethodParameter(
    id            : ParameterId
  , description   : String
  , constraint    : List[Constraint]
  , parameterType : ParameterType.ParameterType
)
final case class TechniqueParameter (
    id   : ParameterId
  , name : ParameterId
  , description : String
)

object ParameterType {
  trait ParameterType
  case object StringParameter extends ParameterType
  case object Raw extends ParameterType
  case object HereString extends ParameterType

  trait ParameterTypeService {
    def create(value : String) : PureResult[ParameterType]
    def value(parameterType: ParameterType) : PureResult[String]
    def translate(value : String, paramType : ParameterType, agentType: AgentType) : PureResult[String]
  }

  class BasicParameterTypeService extends  ParameterTypeService {
    def create(value : String) = {
      value match {
        case "string"     => Right(StringParameter)
        case "raw"        => Right(Raw)
        case "HereString" => Right(HereString)
        case _            => Left(Unexpected(s"'${value}' is not a valid method parameter type"))
      }
    }

    def value(parameterType: ParameterType) = {
      parameterType match {
        case StringParameter => Right("string")
        case Raw             => Right("raw")
        case HereString      => Right("HereString")
        case _               => Left(Unexpected(s"parameter type '${parameterType}' has no value defined"))
      }
    }
    def translate(value : String, paramType : ParameterType, agentType: AgentType) : PureResult[String] = {
      (paramType,agentType) match {
        case (Raw,_) => Right(value)
        case (StringParameter | HereString, AgentType.CfeCommunity | AgentType.CfeEnterprise) =>
          Right(s""""${value.replaceAll("""(?<!\\)"""", """\\"""").replaceAll("""(?<!\\)\\(?!(\\|"|'))""","""\\\\""")}"""")
        case (HereString, AgentType.Dsc) => Right(
         s"""@'
            |${value}
            |'@""".stripMargin)
        case (StringParameter, AgentType.Dsc) => Right(s""""${value.replaceAll("\"", "`\"")}"""")
        case (_, _) => Left(Unexpected("Cannot translate"))
      }
    }
  }
  
  class PlugableParameterTypeService extends ParameterTypeService {



    def create(value : String) = {
      (innerServices foldRight (Left(Unexpected(s"'${value}' is not a valid method parameter type")) : PureResult[ParameterType])) {
        case(_, res @ Right(_)) => res
        case(service, _) => service.create(value)
      }
    }
    def value(parameterType: ParameterType) = {
      (innerServices foldRight (Left(Unexpected(s"parameter type '${parameterType}' has no value defined"))   : PureResult[String])) {
        case(_, res @ Right(_)) => res
        case(service, _) => service.value(parameterType)
      }
    }

    def translate(value : String, paramType : ParameterType, agentType: AgentType) : PureResult[String] = {
      (innerServices foldRight (Left(Unexpected(s"'${value}' is not a valid method parameter type")) : PureResult[String])) {
        case(_, res @ Right(_)) => res
        case(service, _) => service.translate(value,paramType,agentType)
      }
    }

    def addNewParameterService(service : ParameterTypeService) = innerServices = service :: innerServices
    private[this] var innerServices: List[ParameterTypeService] = new BasicParameterTypeService :: Nil
  }
}



object Constraint {
  sealed trait Constraint {
    def check (value : String) : CheckResult
  }
  import NonEmptyList.one

  case class AllowEmpty(allow : Boolean) extends  Constraint {
    def check(value: String): CheckResult = {
      if (allow || value.nonEmpty) {
        OK
      } else {
        NOK(one("Must not be empty"))
      }
    }
  }

  case class AllowWhiteSpace(allow: Boolean) extends  Constraint {
    def check(value: String): CheckResult = {
      if (allow || Pattern.compile("""^(?!\s).*(?<!\s)$""", Pattern.DOTALL).asPredicate().test(value)) {
        OK
      } else {
        NOK(one("Must not have leading or trailing whitespaces"))
      }
    }
  }
  case class MaxLength(max : Int) extends Constraint {
    def check(value: String): CheckResult = {
      if (value.size <= max) {
        OK
      } else {
        val agentMaxNotice = if (max == 16384) " Fields over 16384 characters are currently not supported. If you want to edit a file, please insert your content into a file, and copy it with a file_copy_* method, or use a template." else ""
        NOK(one(s"Max length is ${max}. Current size is ${value.size}.${agentMaxNotice}"))
      }
    }
  }
  case class MinLength(min : Int) extends Constraint {
    def check(value: String): CheckResult = {
      if (value.size >= min) {
        OK
      } else {
        NOK(one(s"Min length is ${min}. Current size is ${value.size}."))
      }
    }
  }
  case class MatchRegex(regex : String) extends Constraint {
    def check(value: String): CheckResult = {
      if (value.matches(regex)) {
        OK
      } else {
        NOK(one(s"Must match regex '${regex}'"))
      }
    }
  }
  case class NotMatchRegex(regex : String) extends Constraint {
    def check(value: String): CheckResult = {
      if (!value.matches(regex)) {
        OK
      } else {
        NOK(one(s"Must not match regex '${regex}'"))
      }
    }
  }
  case class FromList(list : List[String]) extends Constraint {
    def check(value: String): CheckResult = {
      if (list.contains(value)) {
        OK
      } else {
        NOK(one(s"Must be an accepted value: ${list.mkString(", ")}"))
      }
    }
  }
  sealed trait CheckResult
  case object OK extends CheckResult
  case class NOK (cause : NonEmptyList[String]) extends CheckResult
}


object CheckConstraint  {
  def check(constraint: List[Constraint.Constraint], value : String) : CheckResult = {
    import Constraint._

    constraint.map(_.check(value)).foldRight(OK : CheckResult) {
      case (OK, OK) => OK
      case (NOK(m1), NOK(m2)) => NOK(m1 ::: m2)
      case (res:NOK,_) => res
      case (_,res:NOK) => res
    }
  }
}

class TechniqueSerializer(parameterTypeService: ParameterTypeService) {

  import net.liftweb.json.JsonDSL._
  def serializeTechniqueMetadata(technique : ncf.Technique) : JValue = {

    def serializeTechniqueParameter(parameter : TechniqueParameter) : JValue = {
      ( ( "id"   -> parameter.id.value   )
        ~ ( "name" -> parameter.name.value )
        ~ ( "description" -> parameter.description )
        )
    }
    def serializeMethodCall(call : MethodCall) : JValue = {
      val params : JValue = call.parameters.map {
        case (parameterName, value) =>
          ( ( "name"  -> parameterName.value )
          ~ ( "value" -> value               )
          )
      }

      ( ( "method_name"     -> call.methodId.value    )
        ~ ( "class_context" -> call.condition         )
        ~ ( "component"     -> call.component         )
        ~ ( "args"          -> call.parameters.map(_._2) )
        ~ ( "parameters"    -> params                 )
        )
    }

    def serializeResource(resourceFile: ResourceFile) = {
      ( ( "name"  -> resourceFile.path        )
        ~ ( "state" -> resourceFile.state.value )
        )
    }

    val resource = technique.ressources.map(serializeResource)
    val parameters = technique.parameters.map(serializeTechniqueParameter).toList
    val calls = technique.methodCalls.map(serializeMethodCall).toList
    ( ( "bundle_name"  -> technique.bundleName.value )
      ~ ( "version"      -> technique.version.value    )
      ~ ( "category"     -> technique.category         )
      ~ ( "description"  -> technique.description      )
      ~ ( "name"         -> technique.name             )
      ~ ( "method_calls" -> calls                      )
      ~ ( "parameter"    -> parameters                 )
      ~ ( "resources"    -> resource                   )
      )
  }


  def serializeMethodMetadata(method : GenericMethod) : JValue = {
    def serializeMethodParameter(param: MethodParameter) : JValue = {

      def serializeMethodConstraint(constraint: ncf.Constraint.Constraint) : JField = {
        constraint match {
          case ncf.Constraint.AllowEmpty(allow)      => JField("allow_empty_string", allow)
          case ncf.Constraint.AllowWhiteSpace(allow) => JField("allow_whitespace_string", allow)
          case ncf.Constraint.MaxLength(max)         => JField("max_length", max)
          case ncf.Constraint.MinLength(min)         => JField("min_length", min)
          case ncf.Constraint.MatchRegex(re)         => JField("regex", re)
          case ncf.Constraint.NotMatchRegex(re)      => JField("not_regex", re)
          case ncf.Constraint.FromList(list)         => JField("select", list)
        }
      }
      val constraints = JObject(param.constraint.map(serializeMethodConstraint))
      val paramType = JString(parameterTypeService.value(param.parameterType).getOrElse("Unknown"))
      ( ( "name"        -> param.id.value    )
        ~ ( "description" -> param.description )
        ~ ( "constraints" -> constraints       )
        ~ ( "type"        -> paramType         )
        )
    }
    def serializeAgentSupport(agent : AgentType) = {
      agent match  {
        case AgentType.Dsc => JString("dsc")
        case AgentType.CfeCommunity | AgentType.CfeEnterprise => JString("cfengine-community")
      }
    }
    val parameters = method.parameters.map(serializeMethodParameter)
    val agentSupport = method.agentSupport.map(serializeAgentSupport)
    ( ( "bundle_name"     -> method.id.value             )
      ~ ( "name"            -> method.name                 )
      ~ ( "description"     -> method.description          )
      ~ ( "class_prefix"    -> method.classPrefix          )
      ~ ( "class_parameter" -> method.classParameter.value )
      ~ ( "agent_support"   -> agentSupport                )
      ~ ( "parameter"       -> parameters                  )
      ~ ( "documentation"   -> method.documentation        )
      )

  }

}