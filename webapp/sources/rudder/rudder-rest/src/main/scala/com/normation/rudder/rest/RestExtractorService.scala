/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.rest

import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.NodeId
import com.normation.rudder.api.{AclPath, ApiAccountId, ApiAccountName, ApiAclElement, ApiAuthorizationKind, HttpAction, ApiAuthorization => ApiAuthz}
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.queries.NodeReturnType
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.QueryReturnType
import com.normation.rudder.domain.workflows._
import com.normation.rudder.repository._
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.CmdbQueryParser._
import com.normation.rudder.services.queries.JsonQueryLexer
import com.normation.rudder.services.queries.StringCriterionLine
import com.normation.rudder.services.queries.StringQuery
import com.normation.rudder.rest.data._
import com.normation.utils.Control._
import net.liftweb.common._
import net.liftweb.http.Req
import net.liftweb.json.{JObject, _}
import net.liftweb.json.JsonDSL._
import com.normation.rudder.repository.json.DataExtractor.CompleteJson
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.nodes.NodePropertyProvider
import com.normation.rudder.web.services.UserPropertyService
import com.normation.rudder.web.services.ReasonBehavior
import com.normation.rudder.repository.ldap.NodeStateEncoder
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.ncf.GenericMethod
import com.normation.rudder.ncf.MethodCall
import com.normation.rudder.ncf.ParameterId
import com.normation.rudder.ncf.MethodParameter
import com.normation.rudder.ncf.TechniqueParameter
import com.normation.rudder.ncf.{Technique => NcfTechnique}
import com.normation.rudder.api.ApiAuthorizationKind
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.rudder.web.components.DateFormaterService
import com.normation.utils.Control

import com.normation.box._

case class RestExtractorService (
    readRule             : RoRuleRepository
  , readDirective        : RoDirectiveRepository
  , readGroup            : RoNodeGroupRepository
  , techniqueRepository  : TechniqueRepository
  , queryParser          : CmdbQueryParser with JsonQueryLexer
  , userPropertyService  : UserPropertyService
  , workflowLevelService : WorkflowLevelService
) extends Loggable {

  import com.normation.rudder.repository.json.DataExtractor.OptionnalJson._
  /*
   * Params Extractors
   */

  private[this] def extractOneValue[T] (params : Map[String, List[String]], key : String)( to : (String) => Box[T] = ( (value:String) => Full(value))) = {
    params.get(key) match {
      case None               => Full(None)
      case Some(value :: Nil) => to(value).map(Some(_))
      case _                  => Failure(s"updateRule should contain only one value for $key")
    }
  }

  private[this] def extractList[T] (params : Map[String, List[String]], key: String)(to: (List[String]) => Box[T]) : Box[Option[T]] = {
    params.get(key) match {
      case None       => Full(None)
      case Some(list) => to(list).map(Some(_))
    }
  }

  /*
   * Convert value functions
   */
  private[this] def toBoolean (value : String) : Box[Boolean] = {
    value match {
      case "true"  => Full(true)
      case "false" => Full(false)
      case _       => Failure(s"value for boolean should be true or false instead of ${value}")
    }
  }

  private[this] def toNodeStatusAction (value : String) : Box[NodeStatusAction] = {
    value.toLowerCase match {
      case "accept" | "accepted"  => Full(AcceptNode)
      case "refuse" | "refused" => Full(RefuseNode)
      case "delete" | "deleted" | "removed" => Full(DeleteNode)
      case _       => Failure(s"value for nodestatus action should be accept, refuse, delete")
    }
  }
  private[this] def toInt (value:String) : Box[Int] = {
    try {
      Full(value.toInt)
    } catch  {
      case _ : java.lang.NumberFormatException => Failure(s"value for integer should be an integer instead of ${value}")
    }
  }

  private[this] def toQuery (value:String) : Box[Query] = {
    queryParser(value)
  }

  private[this] def toQueryCriterion (value:String) : Box[List[StringCriterionLine]] = {
    JsonParser.parseOpt(value) match {
      case None => Failure("Could not parse 'select' cause in api query ")
      case Some(value) =>
        // Need to encapsulate this in a json Object, so it parse correctly
        val json  = (CRITERIA -> value)
        queryParser.parseCriterionLine(json)
    }
  }

  private[this] def toQueryReturnType (value:String) : Box[QueryReturnType] = {
    QueryReturnType(value).toBox
  }

  private[this] def toQueryComposition (value:String) : Box[Option[String]] = {
    Full(Some(value))
  }

  private[this] def toMinimalSizeString (minimalSize : Int) (value:String) : Box[String] = {
    if (value.size >= minimalSize){
      Full(value)
    }
    else {
      Failure(s"$value must be at least have a ${minimalSize} character size")
    }
  }

  private[this] def toParameterName (value:String) : Box[ParameterName] = {
      toMinimalSizeString(1)(value) match {
        case Full(value) =>
          if (ParameterName.patternName.matcher(value).matches)
            Full(ParameterName(value))
          else Failure(s"Parameter Name should be respect the following regex : ${ParameterName.patternName.pattern()}")

        case eb : EmptyBox => eb ?~! "Parameter Name should not be empty"
      }
  }

  private[this] def toDirectiveParam (value:String) : Box[Map[String,Seq[String]]] = {
    parseSectionVal(parse(value)).map(SectionVal.toMapVariables(_))
  }

  private[this] def extractJsonDirectiveParam (json: JValue ): Box[Option[Map[String,Seq[String]]]] = {
    json \ "parameters" match {
      case JObject(Nil) => Full(None)
      case x@JObject(_) => parseSectionVal(x).map(x => Some(SectionVal.toMapVariables(x)))
      case _            => Failure(s"The value for parameter 'parameters' is malformed.")
    }
  }

  private[this] def toNodeGroupCategoryId (value:String) : Box[NodeGroupCategoryId] = {
    readGroup.getGroupCategory(NodeGroupCategoryId(value)).map(_.id).toBox ?~ s"Node group '$value' not found"
  }
  private[this] def toRuleCategoryId (value:String) : Box[RuleCategoryId] = {
  Full(RuleCategoryId(value))
  //Call to   readRule.getRuleCategory(NodeGroupCategoryId(value)).map(_.id) ?~ s"Directive '$value' not found"
  }

  private[this] def toGroupCategoryId (value:String) : Box[NodeGroupCategoryId] = {
    Full(NodeGroupCategoryId(value))
  }
  private[this] def toDirectiveId (value:String) : Box[DirectiveId] = {
    readDirective.getDirective(DirectiveId(value)).notOptional(s"Directive '$value' not found").map(_.id).toBox
  }

  private[this] def toApiAccountId (value:String) : Box[ApiAccountId] = {
    Full(ApiAccountId(value))
  }

  private[this] def toApiAccountName (value:String) : Box[ApiAccountName] = {
    Full(ApiAccountName(value))
  }

  private[this] def toWorkflowStatus (value : String) : Box[Seq[WorkflowNodeId]] = {
    val possiblestates = workflowLevelService.getWorkflowService().stepsValue
    value.toLowerCase match {
      case "open" => Full(workflowLevelService.getWorkflowService().openSteps)
      case "closed" => Full(workflowLevelService.getWorkflowService().closedSteps)
      case "all" =>  Full(possiblestates)
      case value => possiblestates.find(_.value.toLowerCase == value) match {
        case Some(state) => Full(Seq(state))
        case None => Failure(s"'${value}' is not a possible state for change requests")
      }
    }
  }

  private[this] def toWorkflowTargetStatus (value : String) : Box[WorkflowNodeId] = {
    val possiblestates = workflowLevelService.getWorkflowService().stepsValue
    possiblestates.find(_.value.toLowerCase == value.toLowerCase) match {
      case Some(state) => Full(state)
      case None => Failure(s"'${value}' is not a possible state for change requests, availabled values are: ${possiblestates.mkString("[ ", ", ", " ]")}")
    }
  }

  private[this] def toNodeDetailLevel (value:String) : Box[NodeDetailLevel] = {
    val fields = value.split(",")
    if (fields.contains("full")) {
       Full(FullDetailLevel)
    } else {
      val base = {
        if (fields.contains("minimal")) {
          MinimalDetailLevel
        } else {
          DefaultDetailLevel
        }
      }
      val customFields = fields.filter{
        field =>
          field != "minimal" &&
          field != "default" &&
          NodeDetailLevel.allFields.contains(field)
      }
      if (customFields.isEmpty) {
        Full(base)
      } else {
        val customLevel = CustomDetailLevel(base,customFields.toSet)
        Full(customLevel)
      }
    }
  }

  /*
   * Converting ruletarget.
   *
   * We support two use cases, in both json and parameters mode:
   * - simple rule target name (list of string) =>
   *   converted into a list of included target
   * - full-fledge include/exclude
   *   converted into one composite target.
   *
   * For now, it is an error to give several include/exclude,
   * as of Rudder 3.0, the UI does not know how to handle it.
   *
   * So in all case, the result is exactly ONE target.
   */
  private[this] def toRuleTarget(parameters: Map[String, List[String]], key:String ): Box[Option[RuleTarget]] = {
    parameters.get(key) match {
      case Some(values) =>
        sequence(values) { value => RuleTarget.unser(value) }.flatMap { mergeTarget }
      case None => Full(None)
    }
  }

  private[this] def toRuleTarget(json:JValue, key:String ): Box[Option[RuleTarget]] = {
    for {
      targets <- sequence((json \\ key).children) { child =>
                   child match {
                     case JArray(values) =>
                       sequence(values.children) { value => RuleTarget.unserJson(value) }.flatMap { mergeTarget }
                     case x              => RuleTarget.unserJson(x).map(Some(_))
                   }
                 }
      merged  <- mergeTarget(targets.flatten)
    } yield {
      merged
    }
  }

  private[this] def mergeTarget(seq: Seq[RuleTarget]): Box[Option[RuleTarget]] = {
    seq match {
      case Seq() => Full(None)
      case head +: Seq() => Full(Some(head))
      case several =>
        //if we have only simple target, build a composite including
        if(several.exists( x => x.isInstanceOf[CompositeRuleTarget])) {
          Failure("Composing several composite target with include/exclude is not supported now, please only one composite target.")
        } else {
          Full(Some(RuleTarget.merge(several.toSet)))
        }
    }
  }

  /*
   * Convert List Functions
   */
  private[this] def convertListToDirectiveId (values : Seq[String]) : Box[Set[DirectiveId]] = {
    sequence(values){ toDirectiveId }.map(_.toSet)
  }

  private[this] def convertListToNodeId (values : List[String]) : Box[List[NodeId]] = {
    Full(values.map(NodeId(_)))
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
        case JObject( JField("section",values) :: Nil) => Full(values)
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
    extractOneValue(params, "prettify")(toBoolean).map(_.getOrElse(false)).getOrElse(false)
  }

  def extractReason (req : Req) : Box[Option[String]] = {
    import ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled  => Full(None)
      case mode =>
        val reason = extractString("reason")(req)(Full(_))
        mode match {
          case Mandatory =>
            reason match {
              case Full(None) =>  Failure("Reason field is mandatory and should be at least 5 characters long")
              case Full(Some(v)) if v.size < 5 => Failure("Reason field should be at least 5 characters long")
              case _ => reason
            }
          case Optionnal => reason
        }
    }
  }

  def extractChangeRequestName (req : Req) : Box[Option[String]] = {
    extractString("changeRequestName")(req)(Full(_))
  }

  def extractChangeRequestDescription (req : Req) : String = {
    extractString("changeRequestDescription")(req)(Full(_)).getOrElse(None).getOrElse("")
  }

  def extractNodeStatus (params : Map[String,List[String]]) : Box[NodeStatusAction] = {
    extractOneValue(params, "status")(toNodeStatusAction) match {
      case Full(Some(status)) => Full(status)
      case Full(None) => Failure("node status should not be empty")
      case eb:EmptyBox => eb ?~ "error with node status"
    }
  }

  def extractParameterName (params : Map[String,List[String]]) : Box[ParameterName] = {
     extractOneValue(params, "id")(toParameterName) match {
       case Full(None) => Failure("Parameter id should not be empty")
       case Full(Some(value)) => Full(value)
       case eb:EmptyBox => eb ?~ "Error while fetch parameter Name"
     }
  }

  def extractWorkflowStatus (params : Map[String,List[String]]) : Box[Seq[WorkflowNodeId]] = {
    extractOneValue(params, "status")(toWorkflowStatus) match {
       case Full(None) => Full(workflowLevelService.getWorkflowService().openSteps)
       case Full(Some(value)) => Full(value)
       case eb:EmptyBox => eb ?~ "Error while fetching workflow status"
     }
  }

  def extractWorkflowTargetStatus (params : Map[String,List[String]]) : Box[WorkflowNodeId] = {
    extractOneValue(params, "status")(toWorkflowTargetStatus) match {
      case Full(Some(value)) => Full(value)
      case Full(None) => Failure("workflow status should not be empty")
      case eb:EmptyBox => eb ?~ "Error while fetching workflow status"
    }
  }

  def extractChangeRequestInfo (params : Map[String,List[String]]) : Box[APIChangeRequestInfo] = {
   def ident = (value : String) => Full(value)
   for {
     name        <- extractOneValue(params, "name")(ident)
     description <- extractOneValue(params, "description")(ident)
   } yield {
     APIChangeRequestInfo(name,description)
   }
  }

  def extractNodeIds (params : Map[String,List[String]]) : Box[Option[List[NodeId]]] = {
    extractList(params, "nodeId")(convertListToNodeId)
  }

  def extractTechnique(optTechniqueName: Option[TechniqueName], opTechniqueVersion: Option[TechniqueVersion]) :  Box[Technique] = {
    optTechniqueName match {
      case Some(techniqueName) =>
        opTechniqueVersion match {
          case Some(version) =>
            techniqueRepository.getTechniqueVersions(techniqueName).find(_ == version) match {
              case Some(version) => techniqueRepository.get(TechniqueId(techniqueName,version)) match {
                case Some(technique) => Full(technique)
                case None => Failure(s" Technique ${techniqueName} version ${version} is not a valid Technique")
              }
              case None => Failure(s" version ${version} of Technique ${techniqueName}  is not valid")
            }
          case None => techniqueRepository.getLastTechniqueByName(techniqueName) match {
            case Some(technique) => Full(technique)
            case None => Failure( s"Error while fetching last version of technique ${techniqueName}")
          }
        }
      case None => Failure("techniqueName should not be empty")
    }
  }

  def checkTechniqueVersion (techniqueName: TechniqueName, techniqueVersion: Option[TechniqueVersion])  = {
     techniqueVersion match {
          case Some(version) =>
            techniqueRepository.getTechniqueVersions(techniqueName).find(_ == version) match {
              case Some(version) => Full(Some(version))
              case None => Failure(s" version ${version} of Technique ${techniqueName}  is not valid")
            }
          case None => Full(None)
     }
  }

  def extractNodeGroupCategoryId (params :  Map[String,List[String]]) : Box[NodeGroupCategoryId] ={
    extractOneValue(params, "nodeGroupCategory")(toNodeGroupCategoryId) match {
      case Full(Some(category)) => Full(category)
      case Full(None) => Failure("nodeGroupCategory cannot be empty")
      case eb:EmptyBox => eb ?~ "error when deserializing node group category"
    }
  }

  def toTag (s : String) : Box[Tag] = {
    import Tag._
    val list =  s.split(":")
    val name = list.headOption.getOrElse(s)
    val value = list.tail.headOption.getOrElse("")
    Full(Tag(name,value))
  }
  def extractRule (params : Map[String,List[String]]) : Box[RestRule] = {

    for {
      name             <- extractOneValue(params,"displayName")(toMinimalSizeString(3))
      category         <- extractOneValue(params, "category")(toRuleCategoryId)
      shortDescription <- extractOneValue(params,"shortDescription")()
      longDescription  <- extractOneValue(params,"longDescription")()
      enabled          <- extractOneValue(params,"enabled")( toBoolean)
      directives       <- extractList(params,"directives")( convertListToDirectiveId)
      target           <- toRuleTarget(params,"targets")
      tagsList         <- extractList(params, "tags")(sequence(_)(toTag))
      tags             = tagsList.map(t => Tags(t.toSet))
    } yield {
      RestRule(name, category, shortDescription, longDescription, directives, target.map(Set(_)), enabled, tags)
    }
  }

  def extractRuleCategory (params : Map[String,List[String]]) : Box[RestRuleCategory] = {

    for {
      name        <- extractOneValue(params,"name")(toMinimalSizeString(3))
      description <- extractOneValue(params, "description")()
      parent      <- extractOneValue(params,"parent")(toRuleCategoryId)
    } yield {
      RestRuleCategory(name, description, parent)
    }
  }

  def extractGroup (params : Map[String,List[String]]) : Box[RestGroup] = {
    for {
      name        <- extractOneValue(params, "displayName")(toMinimalSizeString(3))
      description <- extractOneValue(params, "description")()
      enabled     <- extractOneValue(params, "enabled")( toBoolean)
      dynamic     <- extractOneValue(params, "dynamic")( toBoolean)
      query       <- extractOneValue(params, "query")(toQuery)
      _           <- if (query.map(_.criteria.size > 0).getOrElse(true)) Full("Query has at least one criteria") else Failure("Query should containt at least one criteria")
      category    <- extractOneValue(params, "category")(toGroupCategoryId)
    } yield {
      RestGroup(name,description,query,dynamic,enabled,category)
    }
  }

  def extractGroupCategory (params : Map[String,List[String]]) : Box[RestGroupCategory] = {

    for {
      name        <- extractOneValue(params,"name")(toMinimalSizeString(3))
      description <- extractOneValue(params, "description")()
      parent      <- extractOneValue(params,"parent")(toNodeGroupCategoryId)
    } yield {
      RestGroupCategory(name, description, parent)
    }
  }

  def extractParameter (params : Map[String,List[String]]) : Box[RestParameter] = {
    for {
      description <- extractOneValue(params, "description")()
      overridable <- extractOneValue(params, "overridable")( toBoolean)
      value       <- extractOneValue(params, "value")()
    } yield {
      RestParameter(value,description,overridable)
    }
  }

  /*
   * Looking for parameter: "properties=foo=bar"
   * ==> set foo to bar; delete baz, set plop to plop.
   */
  def extractNodeProperties (params : Map[String, List[String]]) : Box[Option[Seq[NodeProperty]]] = {
    // properties coming from the API are always provider=rudder / mode=read-write

    extractList(params, "properties") { props =>
      val splitted = props.map { prop =>
        val parts = prop.split('=')
        if(parts.size == 1) NodeProperty(parts(0), "", None)
        //here, we should parse parts(1) and only fallback to string if not successful.
        else NodeProperty(parts(0), parts(1), None)
      }
      Full(splitted)
    }
  }

  def extractNode (params : Map[String, List[String]]) : Box[RestNode] = {
    for {
      properties <- extractNodeProperties(params)
      mode       <- extractOneValue(params, "policyMode")(PolicyMode.parseDefault(_).toBox)
      state      <- extractOneValue(params, "state")(x => NodeStateEncoder.dec(x).toOption)
    } yield {
      RestNode(properties, mode, state)
    }
  }

  /*
   * expecting json:
   * { "properties": [
   *    {"name":"foo" , "value":"bar"  }
   * ,  {"name":"baz" , "value": ""    }
   * ,  {"name":"plop", "value":"plop" }
   * ] }
   */

  private[this] def extractNodeProperty(json : JValue) : Box[NodeProperty] = {
    ( (json \ "name"), (json \ "value") ) match {
      case ( JString(nameValue), value ) =>
        val provider = (json \ "provider") match {
          case JString(value) => Some(NodePropertyProvider(value))
          //if not defined of not a string, use default
          case _              => None
        }

        Full(NodeProperty(nameValue, value, provider))
      case (a, b)  =>
        Failure(s"""Error when trying to parse new property: '${compactRender(json)}'. The awaited format is: {"name": string, "value": json}""")
    }
  }

  def extractNodePropertiesrFromJSON (json : JValue) : Box[RestNodeProperties] = {
    import com.normation.utils.Control.sequence
    for {
      props <- json \ "properties" match {
        case JArray(props) => Full(props)
        case x             => Failure(s"""Error: the given parameter is not a JSON object with a 'properties' key""")
      }
      seq   <- sequence(props) { extractNodeProperty}
    } yield {
      RestNodeProperties(Some(seq))
    }
  }

  def extractNodePropertiesFromJSON (json : JValue) : Box[Option[Seq[NodeProperty]]] = {
    import com.normation.utils.Control.sequence
    json \ "properties" match {
        case JArray(props) => sequence(props){extractNodeProperty}.map(Some(_))
        case JNothing      => Full(None)
        case x             => Failure(s"""Error: the given parameter is not a JSON object with a 'properties' key""")
    }
  }

  def extractNodeFromJSON (json : JValue) : Box[RestNode] = {
    for {
      properties <- extractNodePropertiesFromJSON(json)
      mode       <- extractJsonString(json, "policyMode", PolicyMode.parseDefault(_).toBox)
      state      <- extractJsonString(json, "state", x => NodeStateEncoder.dec(x).toOption)
    } yield {
      RestNode(properties, mode, state)
    }
  }

  /*
   * Looking for parameter: "level=2"
   */
  def extractComplianceLevel(params : Map[String, List[String]]) : Box[Option[Int]] = {
    params.get("level") match {
      case None | Some(Nil) => Full(None)
      case Some(h :: tail) => //only take into account the first level param is several are passed
        try { Full(Some(h.toInt)) }
        catch {
          case ex:NumberFormatException => Failure(s"level (displayed level of compliance details) must be an integer, was: '${h}'")
        }
    }
  }

  def extractDirective(req : Req) : Box[RestDirective] = {
    req.json match {
      case Full(json) => extractDirectiveFromJSON(json)
      case _          => extractDirective(req.params)
    }
  }

  def extractDirective (params : Map[String,List[String]]) : Box[RestDirective] = {
    for {
      name             <- (extractOneValue(params, "name")(toMinimalSizeString(3)), extractOneValue(params, "displayName")(toMinimalSizeString(3))) match {
                            case (res@Full(Some(name)),_) => res
                            case (_,res@Full(Some(name))) => res
                            case (Full(None),Full(None)) => Full(None)
                            case (eb : EmptyBox,_) => eb
                            case (_, eb: EmptyBox) => eb
                          }
      shortDescription <- extractOneValue(params, "shortDescription")()
      longDescription  <- extractOneValue(params, "longDescription")()
      enabled          <- extractOneValue(params, "enabled")( toBoolean)
      priority         <- extractOneValue(params, "priority")(toInt)
      parameters       <- extractOneValue(params, "parameters")(toDirectiveParam)
      techniqueName    <- extractOneValue(params, "techniqueName")(x => Full(TechniqueName(x)))
      techniqueVersion <- extractOneValue(params, "techniqueVersion")(x => Full(TechniqueVersion(x)))
      policyMode       <- extractOneValue(params, "policyMode")(PolicyMode.parseDefault(_).toBox)
      tagsList         <- extractList(params, "tags")(sequence(_)(toTag))
      tags             = tagsList.map(t => Tags(t.toSet))
    } yield {
      RestDirective(name,shortDescription,longDescription,enabled,parameters,priority, techniqueName, techniqueVersion, policyMode,tags)
    }
  }

  def toTagJson(json : JValue) = {
    import Tag._
    json match { case JObject(JField(name,JString(value)) :: Nil) => Full(Tag(name,value))
    case _ => Failure("Not valid format for tags")
    }
  }

  def extractRuleFromJSON (json : JValue) : Box[RestRule] = {
    for {
      name             <- extractJsonString(json, "displayName", toMinimalSizeString(3))
      category         <- extractJsonString(json, "category", toRuleCategoryId)
      shortDescription <- extractJsonString(json, "shortDescription")
      longDescription  <- extractJsonString(json, "longDescription")
      directives       <- extractJsonListString(json, "directives", convertListToDirectiveId)
      target           <- toRuleTarget(json, "targets")
      enabled          <- extractJsonBoolean(json,"enabled")
      tags             <- extractTagsFromJson(json \ "tags")  ?~! "Error when extracting Rule tags"
    } yield {
      RestRule(name, category, shortDescription, longDescription, directives, target.map(Set(_)), enabled, tags)
    }
  }

  def extractRuleCategory ( json : JValue ) : Box[RestRuleCategory] = {
    for {
      name        <- extractJsonString(json, "name", toMinimalSizeString(3))
      description <- extractJsonString(json, "description")
      parent      <- extractJsonString(json, "parent", toRuleCategoryId)
    } yield {
      RestRuleCategory(name, description, parent)
    }
  }

  // this extractTagsFromJson is exclusively used when updating TAG in the POST API request. We want to extract tags as a List
  // of {key1,value1 ... keyN,valueN}

 private[this] def extractTagsFromJson(value:JValue): Box[Option[Tags]] = {
    implicit val formats = DefaultFormats
    for {
      jobjects <- Box(value.extractOpt[List[JObject]]) ?~! s"Invalid JSON serialization for Tags ${value}"
      // be careful, we need to use JObject.obj to get the list even if there is duplicated keys,
      // which would be removed with JObject.values
      pairs    <- Control.sequence(jobjects) { o => Control.sequence(o.obj) { case JField(key, v) =>
        v match {
          case JString(s) if(s.nonEmpty) => Full((key, s))
          case _                         => Failure(s"Cannot parse value '${v}' as a valid tag value for tag with name '${key}'")

        }
      }}
    } yield {
      val tags = pairs.flatten
      Some(Tags(tags.map{case(k,v) => Tag(TagName(k),TagValue(v))}.toSet))
    }
  }


  def extractDirectiveFromJSON (json : JValue) : Box[RestDirective] = {
    for {
      name             <- (extractJsonString(json, "name", toMinimalSizeString(3)), extractJsonString(json, "displayName", toMinimalSizeString(3))) match {
                            case (res@Full(Some(name)),_) => res
                            case (_,res@Full(Some(name))) => res
                            case (Full(None),Full(None)) => Full(None)
                            case (eb : EmptyBox,_) => eb
                            case (_, eb: EmptyBox) => eb
                          }
      shortDescription <- extractJsonString(json, "shortDescription")
      longDescription  <- extractJsonString(json, "longDescription")
      enabled          <- extractJsonBoolean(json, "enabled")
      priority         <- extractJsonInt(json,"priority")
      parameters       <- extractJsonDirectiveParam(json)
      techniqueName    <- extractJsonString(json, "techniqueName", x => Full(TechniqueName(x)))
      techniqueVersion <- extractJsonString(json, "techniqueVersion", x => Full(TechniqueVersion(x)))
      policyMode       <- extractJsonString(json, "policyMode", PolicyMode.parseDefault(_).toBox)
      tags             <- extractTagsFromJson(json \ "tags")  ?~! "Error when extracting Directive tags"
    } yield {
      RestDirective(name,shortDescription,longDescription,enabled,parameters,priority,techniqueName,techniqueVersion,policyMode,tags)
    }
  }

  def extractGroupFromJSON (json : JValue) : Box[RestGroup] = {
    for {
      name        <- extractJsonString(json, "displayName", toMinimalSizeString(3))
      description <- extractJsonString(json, "description")
      enabled     <- extractJsonBoolean(json, "enabled")
      dynamic     <- extractJsonBoolean(json, "dynamic")
      stringQuery <- queryParser.jsonParse(json \ "query")
      query       <- queryParser.parse(stringQuery)
      _           <- if (query.criteria.size > 0) Full("Query has at least one criteria") else Failure("Query should containt at least one criteria")
      category    <- extractJsonString(json, "category", toGroupCategoryId)
    } yield {
      RestGroup(name,description,Some(query),dynamic,enabled,category)
    }
  }

  def extractGroupCategory ( json : JValue ) : Box[RestGroupCategory] = {
    for {
      name        <- extractJsonString(json,"name", toMinimalSizeString(3))
      description <- extractJsonString(json, "description")
      parent      <- extractJsonString(json,"parent", toNodeGroupCategoryId)
    } yield {
      RestGroupCategory(name, description, parent)
    }
  }

  def extractParameterNameFromJSON (json : JValue) : Box[ParameterName] = {
     extractJsonString(json, "id", toParameterName) match {
       case Full(None) => Failure("Parameter id should not be empty")
       case Full(Some(value)) => Full(value)
       case eb:EmptyBox => eb ?~ "Error while fetch parameter Name"
     }
  }

  def extractParameterFromJSON (json : JValue) : Box[RestParameter] = {
    for {
      description <- extractJsonString(json, "description")
      overridable <- extractJsonBoolean(json, "overridable")
      value       <- extractJsonString(json, "value")
    } yield {
      RestParameter(value,description,overridable)
    }
  }


  /*
   * The ACL list which is exchange between
   */
  def extractApiACLFromJSON (json : JValue) : Box[(AclPath, HttpAction)] = {
    import com.normation.rudder.utils.Utils.eitherToBox
    for {
      path   <- CompleteJson.extractJsonString(json, "path", AclPath.parse)
      action <- CompleteJson.extractJsonString(json, "verb", HttpAction.parse)
    } yield {
      (path, action)
    }
  }

  def extractApiAccountFromJSON (json : JValue) : Box[RestApiAccount] = {
    import com.normation.rudder.utils.Utils.eitherToBox
    for {
      id          <- extractJsonString(json, "id", toApiAccountId)
      name        <- extractJsonString(json, "name", toApiAccountName)
      description <- extractJsonString(json, "description")
      enabled     <- extractJsonBoolean(json, "enabled")
      oldId       <- extractJsonString(json, "oldId", toApiAccountId)
      expirationDefined <- extractJsonBoolean(json, "expirationDateDefined")
      expirationValue  <- extractJsonString(json, "expirationDate", DateFormaterService.parseDate)
      authType    <- extractJsonString(json, "authorizationType", ApiAuthorizationKind.parse)

      acl     <- extractJsonArray(json \ "acl")(
                       // I need an option here, but this function does not need to return an option
                       // AndThern it to wrap the result in a option, then unwrap it with a getOrElse after it ran
                       (extractApiACLFromJSON _ ).andThen(_.map(Some(_)))
                     ).map(_.getOrElse(Nil))
    } yield {

      val auth = authType match {
        case None => None
        case Some(ApiAuthorizationKind.None) => Some(ApiAuthz.None)
        case Some(ApiAuthorizationKind.RO) => Some(ApiAuthz.RO)
        case Some(ApiAuthorizationKind.RW) => Some(ApiAuthz.RW)
        case Some(ApiAuthorizationKind.ACL) => {
          //group by path to get ApiAclElements
          val acls = acl.groupBy(_._1).map { case (p, seq) =>
            ApiAclElement(p, seq.map(_._2).toSet)
          }.toList
          Some(ApiAuthz.ACL(acls))
        }
      }
      val expiration = expirationDefined match {
        case None => None
        case Some(true) => Some(expirationValue)
        case Some(false) => Some(None)
      }
      RestApiAccount(id, name, description, enabled, oldId, expiration, auth)
    }
  }

  def extractNodeIdsFromJson (json : JValue) : Box[Option[List[NodeId]]] = {
    extractJsonListString(json, "nodeId", convertListToNodeId)
  }

  def extractNodeStatusFromJson (json : JValue) : Box[NodeStatusAction] = {
    extractJsonString(json, "status", toNodeStatusAction) match {
      case Full(Some(status)) => Full(status)
      case Full(None) => Failure("node status should not be empty")
      case eb:EmptyBox => eb ?~ "error with node status"
    }
  }

  def extractNodeDetailLevel (params : Map[String,List[String]]) : Box[NodeDetailLevel] = {
    extractOneValue(params,"include")(toNodeDetailLevel) match {
      case Full(Some(level)) => Full(level)
      case Full(None) => Full(DefaultDetailLevel)
      case eb:EmptyBox => eb ?~ "error with node level detail"
    }
  }

  def extractQuery (params : Map[String,List[String]]) : Box[Option[Query]] = {
    extractOneValue(params,"query")(toQuery) match {
      case Full(None) =>
        extractOneValue(params,CRITERIA)(toQueryCriterion) match {
          case Full(None) => Full(None)
          case Full(Some(Nil)) => Failure("Query should at least contain one criteria")
          case Full(Some(criterion)) =>
            for {
              // Target defaults to NodeReturnType
              optType <- extractOneValue(params,TARGET)(toQueryReturnType)
              returnType = optType.getOrElse(NodeReturnType)

              // Composition defaults to None/And
              optComposition <-extractOneValue(params,COMPOSITION)(toQueryComposition)
              composition = optComposition.getOrElse(None)

              // Query may fail when parsing
              stringQuery = StringQuery(returnType,composition,criterion.toList)
              query <- queryParser.parse(stringQuery)
            } yield {
               Some(query)
            }
          case eb:EmptyBox => eb ?~! "error with query"
        }
      case Full(query) =>
        Full(query)
      case eb:EmptyBox => eb ?~! "error with query"
    }
  }

  def extractString[T](key : String) (req : Req)(fun : String => Box[T]) : Box[Option[T]]  = {
    req.json match {
      case Full(json) => json \ key match {
        case JString(value) => fun(value).map(Some(_))
        case JNothing => Full(None)
        case x => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
      }
      case _ =>
        req.params.get(key) match {
          case None => Full(None)
          case Some(head :: Nil) => fun(head).map(Some(_))
          case Some(list) => Failure(s"${list.size} values defined for '${key}' parameter, only one needs to be defined")
        }
    }
  }

  def extractInt[T](key : String) (req : Req)(fun : BigInt => Box[T]) : Box[Option[T]]  = {
    req.json match {
      case Full(json) => json \ key match {
        case JInt(value) => fun(value).map(Some(_))
        case JNothing => Full(None)
        case x => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
      }
      case _ =>
        req.params.get(key) match {
          case None => Full(None)
          case Some(head :: Nil) => try {
            fun(head.toLong).map(Some(_))
          } catch {
            case e : Throwable =>
              Failure(s"Parsing request parameter '${key}' as an integer failed, current value is '${head}'. Error message is: '${e.getMessage}'.")
          }
          case Some(list) => Failure(s"${list.size} values defined for 'id' parameter, only one needs to be defined")
        }
    }
  }

  def extractBoolean[T](key : String) (req : Req)(fun : Boolean => T) : Box[Option[T]]  = {
    req.json match {
      case Full(json) => json \ key match {
        case JBool(value) => Full(Some(fun(value)))
        case JNothing => Full(None)
        case x => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
      }
      case _ =>
        req.params.get(key) match {
          case None => Full(None)
          case Some(head :: Nil) => try {
            Full(Some(fun(head.toBoolean)))
          } catch {
            case e : Throwable =>
              Failure(s"Parsing request parameter '${key}' as a boolean failed, current value is '${head}'. Error message is: '${e.getMessage}'.")
          }
          case Some(list) => Failure(s"${list.size} values defined for 'id' parameter, only one needs to be defined")
        }
    }
  }

  def extractMap[T,U](key : String)(req : Req)
    ( keyFun : String => T
    , jsonValueFun : JValue => U
    , paramValueFun : String => U
    , paramMapSepartor : String
    ) : Box[Option[Map[T,U]]]  = {
    req.json match {
      case Full(json) => json \ key match {
        case JObject(fields) =>
          val map : Map[T,U] = fields.map{ case JField(fieldName, value) => (keyFun(fieldName), jsonValueFun(value))}.toMap
          Full(Some(map))
        case JNothing => Full(None)
        case x => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
      }
      case _ =>
        req.params.get(key) match {
          case None => Full(None)
          case Some(keyValues) =>
              (bestEffort(keyValues) {
                case keyValue =>
                  val splitted = keyValue.split(paramMapSepartor,1).toList
                  splitted match {
                    case key :: value :: Nil => Full((keyFun(key),paramValueFun(value)))
                    case _ => Failure ("Could not split value")
                  }
              }).map(values => Some(values.toMap))
        }
    }
  }

  def extractObj[T](key : String)(req : Req)(jsonValueFun : JObject => Box[T]) : Box[Option[T]]  = {
    req.json match {
      case Full(json) => extractJsonObj (json, key, jsonValueFun)
      case _ =>
        req.params.get(key) match {
          case None => Full(None)
          case Some(value :: Nil) =>
            parseOpt(value) match {
              case Some(obj : JObject) =>  jsonValueFun(obj).map(Some(_))
              case _ => Failure(s"Not a valid value for '${key}' parameter, current value is : ${value}")
            }
          case Some(list) => Failure(s"${list.size} values defined for '${key}' parameter, only one needs to be defined")
        }
    }
  }

  def extractList[T](key : String) (req : Req)(fun : String => Box[T]) : Box[List[T]]   = {
    req.json match {
      case Full(json) => json \ key match {
        case JString(value) => fun(value).map(_ :: Nil)
        case JArray(values) => com.normation.utils.Control.bestEffort(values){
                                 value =>
                                   value match {
                                     case JString(value) => fun(value)
                                     case x =>
                                       Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
                                   }

                               }.map(_.toList)
        case JNothing => Full(Nil)
        case x => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
      }
      case _ =>
        req.params.get(key) match {
          case None => Full(Nil)
          case Some(list) => bestEffort(list)(fun(_)).map(_.toList)
        }
    }
  }

  def extractId[T] (req : Req)(fun : String => Full[T])  = extractString("id")(req)(fun)

  def extractNcfTechnique (json : JValue, methods: Map[BundleName, GenericMethod]) : Box[NcfTechnique] = {
    for {
      bundleName  <- CompleteJson.extractJsonString(json, "bundle_name", s => Full(BundleName(s)))
      version     <- CompleteJson.extractJsonString(json, "version")
      description <- CompleteJson.extractJsonString(json, "description")
      name        <- CompleteJson.extractJsonString(json, "name")
      calls       <- CompleteJson.extractJsonArray(json \ "method_calls")(extractMethodCall(_, methods))
      parameters  <- CompleteJson.extractJsonArray(json \ "parameter")(extractTechniqueParameter)
    } yield {
      NcfTechnique(bundleName, name, calls, new Version(version), description, parameters)
    }
  }

  def extractMethodCall (json : JValue, methods: Map[BundleName, GenericMethod]) : Box[MethodCall] = {

    for {
      methodId        <- CompleteJson.extractJsonString(json, "method_name", s => Full(BundleName(s)))
      condition       <- CompleteJson.extractJsonString(json, "class_context")
      component       <- CompleteJson.extractJsonString(json, "component")
      parameterValues <- CompleteJson.extractJsonArray(json \ "args"){
                           case JString(value) => Full(value)
                           case s => Failure(s"Invalid format of method call when extracting from json, expecting and array but got : ${s}")
                         }

      parameters = methods.get(methodId).toList.flatMap(_.parameters.map(_.id)).zip(parameterValues).toMap
    } yield {
      MethodCall(methodId, parameters, condition, component)
    }
  }

  def extractMethodParameter(json : JValue) : Box[MethodParameter] = {
    for {
      id          <- CompleteJson.extractJsonString(json, "name", a => Full(ParameterId(a)))
      description <- CompleteJson.extractJsonString(json, "description")
    } yield {
      MethodParameter(id, description)
    }
  }

  def extractTechniqueParameter(json : JValue) : Box[TechniqueParameter] = {
    for {
      id   <- CompleteJson.extractJsonString(json, "id", a => Full(ParameterId(a)))
      name <- CompleteJson.extractJsonString(json, "name", a => Full(ParameterId(a)))
    } yield {
      TechniqueParameter(id, name)
    }
  }

  def extractGenericMethod (json : JValue) : Box[List[GenericMethod]] = {
    CompleteJson.extractJsonArray(json) { json =>
      for {
        bundleName     <- CompleteJson.extractJsonString(json, "bundle_name", s => Full(BundleName(s)))
        description    <- CompleteJson.extractJsonString(json, "description")
        name           <- CompleteJson.extractJsonString(json, "name")
        classPrefix    <- CompleteJson.extractJsonString(json, "class_prefix")
        classParameter <- CompleteJson.extractJsonString(json, "class_parameter", a => Full(ParameterId(a)))
        agentSupport   <- (CompleteJson.extractJsonListString(json, "agent_support", sequence(_) {
                             case "dsc" => Full(AgentType.Dsc :: Nil)
                             case "cfengine-community" => Full(AgentType.CfeCommunity :: AgentType.CfeEnterprise ::Nil)
                             case _ => Failure("invalid agent")
                          })).map(_.flatten)
        parameters     <- CompleteJson.extractJsonArray(json \ "parameter")(extractMethodParameter)
      } yield {
        GenericMethod(bundleName, name, parameters, classParameter, classPrefix, agentSupport, description)
      }
    }

  }
}
