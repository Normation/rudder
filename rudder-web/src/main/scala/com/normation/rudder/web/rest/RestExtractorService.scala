/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.rest

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.web.rest.directive.RestDirective
import com.normation.rudder.web.rest.group.RestGroup
import com.normation.rudder.web.rest.node.AcceptNode
import com.normation.rudder.web.rest.node.DeleteNode
import com.normation.rudder.web.rest.node.NodeStatusAction
import com.normation.rudder.web.rest.node.RefuseNode
import com.normation.rudder.web.rest.rule.RestRule
import com.normation.rudder.web.services.ReasonBehavior.Disabled
import com.normation.rudder.web.services.ReasonBehavior.Mandatory
import com.normation.rudder.web.services.ReasonBehavior.Optionnal
import com.normation.rudder.web.services.UserPropertyService
import com.normation.utils.Control._
import net.liftweb.common._
import net.liftweb.json._
import com.normation.rudder.api.ApiAccountId
import com.normation.rudder.web.rest.parameter.RestParameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.api.ApiAccountName

case class RestExtractorService (
    readRule             : RoRuleRepository
  , readDirective        : RoDirectiveRepository
  , readGroup            : RoNodeGroupRepository
  , techniqueRepository  : TechniqueRepository
  , queryParser          : CmdbQueryParser
  , userPropertyService  : UserPropertyService
) extends Loggable {


  /*
   * Params Extractors
   */
  private[this] def extractOneValue[T] (params : Map[String,List[String]], key : String)( convertTo : (String) => Box[T] = ( (value:String) => Full(value))) = {
    params.get(key) match {
      case None               => Full(None)
      case Some(value :: Nil) => convertTo(value).map(Some(_))
      case _                  => Failure(s"updateRule should contain only one value for $key")
    }
  }

  private[this] def extractList[T] (params : Map[String,List[String]],key : String)( convertTo : (List[String]) => Box[T] = ( (values:List[String]) => Full(values))) : Box[Option[T]] = {
    params.get(key) match {
      case None       => Full(None)
      case Some(list) => convertTo(list).map(Some(_))
    }
  }

  /*
   * JSON extractors
   */
  private[this] def extractOneValueJson[T](json:JValue, key:String )( convertTo : (String) => Box[T] = ( (value:String) => Full(value))) = {
    json \\ key match {
      case JString(value) => convertTo(value).map(Some(_))
      case JObject(Nil)   => Full(None)
      case _              => Failure(s"Not a good value for parameter ${key}")
    }
  }

  private[this] def extractJsonBoolean(json:JValue, key:String ) = {
    json \\ key match {
      case JBool(value)   => Full(Some(value))
      case JObject(Nil)   => Full(None)
      case _              => Failure(s"Not a good value for parameter ${key}")
    }
  }


  private[this] def extractJsonList[T] (json : JValue,key : String)( convertTo : (List[String]) => Box[T] = ( (value:List[String]) => Full(value))) : Box[Option[T]] = {
    json \\ key match {
      case JArray(values) =>
        val list = values.flatMap {
          case JString(value) => Full(value)
          case value          => Failure(s"Not a good value for parameter ${key} and value ${value}")
          }
        convertTo(list).map(Some(_))
      case JObject(Nil)   => Full(None)
      case _              => Failure(s"Not a good value for parameter ${key}")
    }
  }

  /*
   * Convert value functions
   */
  private[this] def convertToBoolean (value : String) : Box[Boolean] = {
    value match {
      case "true"  => Full(true)
      case "false" => Full(false)
      case _       => Failure(s"value for boolean should be true or false instead of ${value}")
    }
  }

  private[this] def convertToNodeStatusAction (value : String) : Box[NodeStatusAction] = {
    value.toLowerCase match {
      case "accept" | "accepted"  => Full(AcceptNode)
      case "refuse" | "refused" => Full(RefuseNode)
      case "delete" | "deleted" | "removed" => Full(DeleteNode)
      case _       => Failure(s"value for nodestatus action should be accept, refuse, delete")
    }
  }
  private[this] def convertToInt (value:String) : Box[Int] = {
    try {
      Full(value.toInt)
    } catch  {
      case _ : java.lang.NumberFormatException => Failure(s"value for integer should be an integer instead of ${value}")
    }
  }

  private[this] def convertToQuery (value:String) : Box[Query] = {
    queryParser(value)
  }

  private[this] def convertToMinimalSizeString (minimalSize : Int) (value:String) : Box[String] = {
    if (value.size >= minimalSize){
      Full(value)
    }
    else {
      Failure(s"$value must be at least have a ${minimalSize} character size")
    }
  }

  private[this] def convertToParameterName (value:String) : Box[ParameterName] = {
      convertToMinimalSizeString(3)(value) match {
        case Full(value) =>
          if (ParameterName.patternName.matcher(value).matches)
            Full(ParameterName(value))
          else Failure(s"Parameter Name should be respect the following regex : ${ParameterName.patternName.pattern()}")

        case eb : EmptyBox => eb ?~! "Parameter Name should be at least 3 characters long"
      }
  }



  private[this] def convertToDirectiveParam (value:String) : Box[Map[String,Seq[String]]] = {
    parseSectionVal(parse(value)).map(SectionVal.toMapVariables(_))
  }


  private[this] def convertToNodeGroupCategoryId (value:String) : Box[NodeGroupCategoryId] = {
    readGroup.getGroupCategory(NodeGroupCategoryId(value)).map(_.id) ?~ s"Directive '$value' not found"
  }

  private[this] def convertToRuleTarget (value:String) : Box[RuleTarget] = {
      RuleTarget.unser(value) match {
        case Some(GroupTarget(groupId)) => readGroup.getNodeGroup(groupId).map(_ => GroupTarget(groupId))
        case Some(otherTarget)          => Full(otherTarget)
        case None                       => Failure(s"${value} is not a valid RuleTarget")
      }
  }

  private[this] def convertToDirectiveId (value:String) : Box[DirectiveId] = {
    readDirective.getDirective(DirectiveId(value)).map(_.id) ?~ s"Directive '$value' not found"
  }

  private[this] def convertToApiAccountId (value:String) : Box[ApiAccountId] = {
    Full(ApiAccountId(value))
  }

  private[this] def convertToApiAccountName (value:String) : Box[ApiAccountName] = {
    Full(ApiAccountName(value))
  }
  /*
   * Convert List Functions
   */
  private[this] def convertListToDirectiveId (values : List[String]) : Box[Set[DirectiveId]] = {
    sequence ( values.filter(_.size != 0) ) ( convertToDirectiveId ).map(_.toSet)

  }

  private[this] def convertListToNodeId (values : List[String]) : Box[List[NodeId]] = {
    Full(values.map(NodeId(_)))
  }

  private[this] def convertListToRuleTarget (values : List[String]) : Box[Set[RuleTarget]] = {
    sequence ( values.filter(_.size != 0) ) ( convertToRuleTarget ).map(_.toSet)
  }

  def parseSectionVal(xml:JValue) : Box[SectionVal] = {

    def parseSectionName(section:JValue) : Box[String] = {
      section \ "name" match {
        case JString(sectionName) => Full(sectionName)
        case a => Failure("Missing required attribute 'name' for <section>: " + section)
      }
    }

    def parseSection(section:JValue) : Box[(String, SectionVal)] = {
      section match {
        case JNothing       => Failure("Missing required tag <section> in: " + xml)
        case JObject( JField("section",values) :: Nil) => recValParseSection(values)
        case _              => Failure("Found several <section> tag in XML, but only one root section is allowed: " + xml)
      }

    }


    def parseSections(section:JValue) : Box[Map[String,Seq[SectionVal]]] = {
      section \ "sections" match {
        case JNothing => Full(Map())
        case JArray(sections) => (sequence (sections.toSeq) (parseSection)).map(_.groupBy(_._1).mapValues(_.map(_._2)))
        case a => Failure("Missing required attribute 'name' for <section>: " + section)
      }
    }

    def parseVar (varSection :JValue) : Box[(String,String)] = {
      varSection match {
        case JObject( JField("var", JObject(JField("name",JString(varName)) :: JField("value",JString(varValue)) :: Nil)) :: Nil) => Full((varName,varValue))
        case a => Failure("Missing required attribute 'name' for <section>: " + varSection)
      }
    }
    def parseSectionVars(section:JValue) : Box[Map[String,String]] = {
      section \ "vars" match {
        case JNothing => Full(Map())
        case JArray(vars) => (sequence (vars.toSeq) (parseVar)).map(_.toMap)
        case a => Failure("Missing required attribute 'name' for <section>: " + section)
      }
    }

    def recValParseSection(section:JValue) : Box[(String, SectionVal)] = {
      for {
        sectionName <- parseSectionName(section)
        vars <- parseSectionVars(section)
        sections <-parseSections(section)
      } yield {
        (sectionName,SectionVal(sections,vars))
      }
    }

    for {
      root <- xml match {
        case JNothing       => Failure("Missing required tag <section> in: " + xml)
        case JObject( JField("section",values) :: Nil) => Full(JField("section",values))
        case _              => Failure("Found several <section> tag in XML, but only one root section is allowed: " + xml)
      }
      (_ , sectionVal) <- recValParseSection(root)
    } yield {
      sectionVal
    }
  }

  /*
   * Data extraction functions
   */
  def extractPrettify (params : Map[String,List[String]]) : Boolean = {
    extractOneValue(params, "prettify")(convertToBoolean).map(_.getOrElse(false)).getOrElse(false)
  }

  def extractReason (params : Map[String,List[String]]) : Box[Option[String]] = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled  => Full(None)
      case Mandatory =>  extractOneValue(params, "reason")(convertToMinimalSizeString(5)) match {
        case Full(None)  => Failure("Reason field is mandatory and should be at least 5 characters long")
        case Full(value) => Full(value)
        case eb:EmptyBox => eb ?~ "Error while extracting mandatory reason field"
      }
      case Optionnal => extractOneValue(params, "reason")()
    }
  }

  def extractChangeRequestName (params : Map[String,List[String]]) : Box[Option[String]] = {
    extractOneValue(params, "changeRequestName")(convertToMinimalSizeString(3))
  }

  def extractChangeRequestDescription (params : Map[String,List[String]]) : String = {
    extractOneValue(params, "changeRequestDescription")().getOrElse(None).getOrElse("")
  }

  def extractNodeStatus (params : Map[String,List[String]]) : Box[NodeStatusAction] = {
    extractOneValue(params, "status")(convertToNodeStatusAction) match {
      case Full(Some(status)) => Full(status)
      case Full(None) => Failure("node status should not be empty")
      case eb:EmptyBox => eb ?~ "error with node status"
    }
  }

  def extractParameterName (params : Map[String,List[String]]) : Box[ParameterName] = {
     extractOneValue(params, "id")(convertToParameterName) match {
       case Full(None) => Failure("Parameter id should not be empty")
       case Full(Some(value)) => Full(value)
       case eb:EmptyBox => eb ?~ "Error while fetch parameter Name"
     }
  }

  def extractNodeIds (params : Map[String,List[String]]) : Box[Option[List[NodeId]]] = {
    extractList(params, "nodeId")(convertListToNodeId)
  }

  def extractTechnique (params : Map[String,List[String]]) :  Box[Technique] = {
    extractOneValue(params, "techniqueName")() match {
      case Full(Some(name)) =>
        val techniqueName = TechniqueName(name)
        extractOneValue(params, "techniqueVersion")() match {
          case Full(Some(version)) =>
            techniqueRepository.getTechniqueVersions(techniqueName).find(_.upsreamTechniqueVersion.value == version) match {
              case Some(version) => techniqueRepository.get(TechniqueId(techniqueName,version)) match {
                case Some(technique) => Full(technique)
                case None => Failure(s" Technique ${techniqueName} version ${version} is not a valid Technique")
              }
              case None => Failure(s" version ${version} of Technique ${techniqueName}  is not valid")
            }
          case Full(None) => techniqueRepository.getLastTechniqueByName(techniqueName) match {
            case Some(technique) => Full(technique)
            case None => Failure( s"Error while fetching last version of technique ${techniqueName}")
          }
        }
      case Full(None) => Failure("techniqueName should not be empty")
      case eb:EmptyBox => eb ?~ "techniqueName should not be empty"
    }
  }

  def extractTechniqueVersion (params : Map[String,List[String]], techniqueName : TechniqueName)  = {
     extractOneValue(params, "techniqueVersion")() match {
          case Full(Some(version)) =>
            techniqueRepository.getTechniqueVersions(techniqueName).find(_.upsreamTechniqueVersion.value == version) match {
              case Some(version) => Full(Some(version))
              case None => Failure(s" version ${version} of Technique ${techniqueName}  is not valid")
            }
          case Full(None) => Full(None)
          case eb:EmptyBox => eb ?~ "error when extracting technique version"
     }
  }

  def extractNodeGroupCategoryId (params :  Map[String,List[String]]) : Box[NodeGroupCategoryId] ={
    extractOneValue(params, "nodeGroupCategory")(convertToNodeGroupCategoryId) match {
      case Full(Some(category)) => Full(category)
      case Full(None) => Failure("nodeGroupCategory cannot be empty")
      case eb:EmptyBox => eb ?~ "error when deserializing node group category"
    }
  }
  def extractRule (params : Map[String,List[String]]) : Box[RestRule] = {

    for {
      name             <- extractOneValue(params,"displayName")(convertToMinimalSizeString(3))
      shortDescription <- extractOneValue(params,"shortDescription")()
      longDescription  <- extractOneValue(params,"longDescription")()
      enabled          <- extractOneValue(params,"enabled")( convertToBoolean)
      directives       <- extractList(params,"directives")( convertListToDirectiveId)
      targets          <- extractList(params,"targets")(convertListToRuleTarget)
    } yield {
      RestRule(name,shortDescription,longDescription,directives,targets,enabled)
    }
  }


  def extractGroup (params : Map[String,List[String]]) : Box[RestGroup] = {
    for {
      name        <- extractOneValue(params, "displayName")(convertToMinimalSizeString(3))
      description <- extractOneValue(params, "description")()
      enabled     <- extractOneValue(params, "enabled")( convertToBoolean)
      dynamic     <- extractOneValue(params, "dynamic")( convertToBoolean)
      query       <- extractOneValue(params, "query")(convertToQuery)
    } yield {
      RestGroup(name,description,query,dynamic,enabled)
    }
  }


  def extractParameter (params : Map[String,List[String]]) : Box[RestParameter] = {
    for {
      description <- extractOneValue(params, "description")()
      overridable <- extractOneValue(params, "overridable")( convertToBoolean)
      value       <- extractOneValue(params, "value")()
    } yield {
      RestParameter(value,description,overridable)
    }
  }

  def extractDirective (params : Map[String,List[String]]) : Box[RestDirective] = {
    for {
      name             <- extractOneValue(params, "displayName")(convertToMinimalSizeString(3))
      shortDescription <- extractOneValue(params, "shortDescription")()
      longDescription  <- extractOneValue(params, "longDescription")()
      enabled          <- extractOneValue(params, "enabled")( convertToBoolean)
      priority         <- extractOneValue(params, "priority")(convertToInt)
      parameters       <- extractOneValue(params, "parameters")(convertToDirectiveParam)
    } yield {
      RestDirective(name,shortDescription,longDescription,enabled,parameters,priority)
    }
  }

  def extractRuleFromJSON (json : JValue) : Box[RestRule] = {
    for {
      name             <- extractOneValueJson(json, "displayName")(convertToMinimalSizeString(3))
      shortDescription <- extractOneValueJson(json, "shortDescription")()
      longDescription  <- extractOneValueJson(json, "longDescription")()
      directives       <- extractJsonList(json, "directives")(convertListToDirectiveId)
      targets          <- extractJsonList(json, "targets")(convertListToRuleTarget)
      enabled          <- extractJsonBoolean(json,"enabled")
    } yield {
      RestRule(name,shortDescription,longDescription,directives,targets,enabled)
    }
  }

  def extractDirectiveFromJSON (json : JValue) : Box[RestDirective] = {
    for {
      name             <- extractOneValueJson(json, "displayName")(convertToMinimalSizeString(3))
      shortDescription <- extractOneValueJson(json, "shortDescription")()
      longDescription  <- extractOneValueJson(json, "longDescription")()
      enabled          <- extractOneValueJson(json, "enabled")( convertToBoolean)
      priority         <- extractOneValueJson(json, "priority")(convertToInt)
      parameters       <- extractOneValueJson(json,  "parameters")(convertToDirectiveParam)
    } yield {
      RestDirective(name,shortDescription,longDescription,enabled,parameters,priority)
    }
  }

  def extractGroupFromJSON (json : JValue) : Box[RestGroup] = {
    for {
      name        <- extractOneValueJson(json, "displayName")(convertToMinimalSizeString(3))
      description <- extractOneValueJson(json, "description")()
      enabled     <- extractOneValueJson(json, "enabled")( convertToBoolean)
      dynamic     <- extractOneValueJson(json, "dynamic")( convertToBoolean)
      query       <- extractOneValueJson(json, "query")(convertToQuery)
    } yield {
      RestGroup(name,description,query,dynamic,enabled)
    }
  }

  def extractParameterNameFromJSON (json : JValue) : Box[ParameterName] = {
     extractOneValueJson(json, "id")(convertToParameterName) match {
       case Full(None) => Failure("Parameter id should not be empty")
       case Full(Some(value)) => Full(value)
       case eb:EmptyBox => eb ?~ "Error while fetch parameter Name"
     }
  }

  def extractParameterFromJSON (json : JValue) : Box[RestParameter] = {
    for {
      description <- extractOneValueJson(json, "description")()
      overridable <- extractOneValueJson(json, "overridable")( convertToBoolean)
      value       <- extractOneValueJson(json, "value")()
    } yield {
      RestParameter(value,description,overridable)
    }
  }

  def extractApiAccountFromJSON (json : JValue) : Box[RestApiAccount] = {
    for {
      id          <- extractOneValueJson(json, "id")(convertToApiAccountId)
      name        <- extractOneValueJson(json, "name")(convertToApiAccountName)
      description <- extractOneValueJson(json, "description")()
      enabled     <- extractJsonBoolean(json, "enabled")
      oldName     <- extractOneValueJson(json, "oldId")(convertToApiAccountId)
    } yield {
      RestApiAccount(id, name, description, enabled, oldName)
    }
  }



}