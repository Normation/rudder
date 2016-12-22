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

package com.normation.rudder.web.rest

import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.repository._
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.CmdbQueryParser._
import com.normation.rudder.web.rest.directive.RestDirective
import com.normation.rudder.web.rest.group.RestGroup
import com.normation.rudder.web.rest.node._
import com.normation.rudder.web.rest.rule.RestRule
import com.normation.rudder.web.services.ReasonBehavior.Disabled
import com.normation.rudder.web.services.ReasonBehavior.Mandatory
import com.normation.rudder.web.services.ReasonBehavior.Optionnal
import com.normation.rudder.web.services.UserPropertyService
import com.normation.utils.Control._
import net.liftweb.util.Helpers.tryo
import net.liftweb.common._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.api.ApiAccountId
import com.normation.rudder.web.rest.parameter.RestParameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.api.ApiAccountName
import com.normation.rudder.domain.workflows._
import com.normation.rudder.web.rest.changeRequest.APIChangeRequestInfo
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries.JsonQueryLexer
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.services.queries.StringCriterionLine
import com.normation.rudder.domain.queries.QueryReturnType
import com.normation.rudder.domain.queries.NodeReturnType
import com.normation.rudder.services.queries.StringQuery
import net.liftweb.http.Req
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.datasources.DataSource
import com.normation.rudder.datasources.DataSourceName
import org.joda.time.Seconds
import net.liftweb.json.JsonAST.JObject
import com.normation.rudder.datasources.DataSourceType
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import com.normation.rudder.datasources.HttpDataSourceType
import com.normation.rudder.datasources.HttpRequestMode
import com.normation.rudder.datasources.OneRequestByNode
import com.normation.rudder.datasources.OneRequestAllNodes
import com.normation.rudder.datasources.DataSourceRunParameters
import com.normation.rudder.datasources.DataSourceSchedule
import com.normation.rudder.datasources.Scheduled
import com.normation.rudder.datasources.NoSchedule

case class RestExtractorService (
    readRule             : RoRuleRepository
  , readDirective        : RoDirectiveRepository
  , readGroup            : RoNodeGroupRepository
  , techniqueRepository  : TechniqueRepository
  , queryParser          : CmdbQueryParser with JsonQueryLexer
  , userPropertyService  : UserPropertyService
  , workflowService      : WorkflowService
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

  private[this] def extractList[T] (params : Map[String,List[String]], key: String)(convertTo: (List[String]) => Box[T] = ( (values:List[String]) => Full(values))) : Box[Option[T]] = {
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

  private[this] def extractJsonInt(json:JValue, key:String ) = {
    json \\ key match {
      case JInt(value)   => Full(Some(value.toInt))
      case JObject(Nil)   => Full(None)
      case _              => Failure(s"Not a good value for parameter ${key}")
    }
  }

  private[this] def extractJsonBigInt[T](json:JValue, key:String )(convertTo : BigInt => Box[T]) = {
    json \\ key match {
      case JInt(value)   => convertTo(value).map(Some(_))
      case JObject(Nil)   => Full(None)
      case _              => Failure(s"Not a good value for parameter ${key}")
    }
  }

  private[this] def extractJsonListString[T] (json: JValue, key: String)( convertTo: List[String] => Box[T] ): Box[Option[T]] = {
    json \\ key match {
      case JArray(values) =>
        for {
          strings <- sequence(values) { _ match {
                        case JString(s) => Full(s)
                        case x => Failure(s"Error extracting a string from json: '${x}'")
                      } }
          converted <- convertTo(strings.toList)
        } yield {
          Some(converted)
        }
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

  private[this] def convertToCriterionLine (value:String) : Box[List[StringCriterionLine]] = {
    JsonParser.parseOpt(value) match {
      case None => Failure("Could not parse 'select' cause in api query ")
      case Some(value) =>
        // Need to encapsulate this in a json Object, so it parse correctly
        val json  = ("where" -> value)
        queryParser.parseCriterionLine(json)
    }
  }

  private[this] def convertToQueryCriterion (value:String) : Box[List[StringCriterionLine]] = {
    JsonParser.parseOpt(value) match {
      case None => Failure("Could not parse 'select' cause in api query ")
      case Some(value) =>
        // Need to encapsulate this in a json Object, so it parse correctly
        val json  = (CRITERIA -> value)
        queryParser.parseCriterionLine(json)
    }
  }

  private[this] def convertToQueryReturnType (value:String) : Box[QueryReturnType] = {
    QueryReturnType(value)
  }

  private[this] def convertToQueryComposition (value:String) : Box[Option[String]] = {
    Full(Some(value))
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

  private[this] def extractJsonDirectiveParam (json: JValue ): Box[Option[Map[String,Seq[String]]]] = {
    json \\ "parameters" match {
      case JObject(Nil) => Full(None)
      case x@JObject(_) => parseSectionVal(x).map(x => Some(SectionVal.toMapVariables(x)))
      case _            => Failure(s"The value for parameter 'parameters' is malformed.")
    }
  }

  private[this] def convertToNodeGroupCategoryId (value:String) : Box[NodeGroupCategoryId] = {
    readGroup.getGroupCategory(NodeGroupCategoryId(value)).map(_.id) ?~ s"Node group '$value' not found"
  }
  private[this] def convertToRuleCategoryId (value:String) : Box[RuleCategoryId] = {
  Full(RuleCategoryId(value))
  //Call to   readRule.getRuleCategory(NodeGroupCategoryId(value)).map(_.id) ?~ s"Directive '$value' not found"
  }

  private[this] def convertToGroupCategoryId (value:String) : Box[NodeGroupCategoryId] = {
    Full(NodeGroupCategoryId(value))
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

  private[this] def convertToWorkflowStatus (value : String) : Box[Seq[WorkflowNodeId]] = {
    val possiblestates = workflowService.stepsValue
    value.toLowerCase match {
      case "open" => Full(workflowService.openSteps)
      case "closed" => Full(workflowService.closedSteps)
      case "all" =>  Full(possiblestates)
      case value => possiblestates.find(_.value.toLowerCase == value) match {
        case Some(state) => Full(Seq(state))
        case None => Failure(s"'${value}' is not a possible state for change requests")
      }
    }
  }

  private[this] def convertToWorkflowTargetStatus (value : String) : Box[WorkflowNodeId] = {
    val possiblestates = workflowService.stepsValue
    possiblestates.find(_.value.toLowerCase == value.toLowerCase) match {
      case Some(state) => Full(state)
      case None => Failure(s"'${value}' is not a possible state for change requests, availabled values are: ${possiblestates.mkString("[ ", ", ", " ]")}")
    }
  }

  private[this] def convertToNodeDetailLevel (value:String) : Box[NodeDetailLevel] = {
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
  private[this] def convertToRuleTarget(parameters: Map[String, List[String]], key:String ): Box[Option[RuleTarget]] = {
    parameters.get(key) match {
      case Some(values) =>
        sequence(values) { value => RuleTarget.unser(value) }.flatMap { mergeTarget }
      case None => Full(None)
    }
  }

  private[this] def convertToRuleTarget(json:JValue, key:String ): Box[Option[RuleTarget]] = {
    json \\ key match {
      case JArray(values) =>
        sequence(values) { value => RuleTarget.unserJson(value) }.flatMap { mergeTarget }
      case x              => RuleTarget.unserJson(x).map(Some(_))
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
    sequence(values){ convertToDirectiveId }.map(_.toSet)
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

  def extractReason (req : Req) : Box[Option[String]] = {
    import com.normation.rudder.web.services.ReasonBehavior._
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

  def extractWorkflowStatus (params : Map[String,List[String]]) : Box[Seq[WorkflowNodeId]] = {
    extractOneValue(params, "status")(convertToWorkflowStatus) match {
       case Full(None) => Full(workflowService.openSteps)
       case Full(Some(value)) => Full(value)
       case eb:EmptyBox => eb ?~ "Error while fetching workflow status"
     }
  }

  def extractWorkflowTargetStatus (params : Map[String,List[String]]) : Box[WorkflowNodeId] = {
    extractOneValue(params, "status")(convertToWorkflowTargetStatus) match {
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
    extractOneValue(params, "nodeGroupCategory")(convertToNodeGroupCategoryId) match {
      case Full(Some(category)) => Full(category)
      case Full(None) => Failure("nodeGroupCategory cannot be empty")
      case eb:EmptyBox => eb ?~ "error when deserializing node group category"
    }
  }

  def extractRule (params : Map[String,List[String]]) : Box[RestRule] = {

    for {
      name             <- extractOneValue(params,"displayName")(convertToMinimalSizeString(3))
      category         <- extractOneValue(params, "category")(convertToRuleCategoryId)
      shortDescription <- extractOneValue(params,"shortDescription")()
      longDescription  <- extractOneValue(params,"longDescription")()
      enabled          <- extractOneValue(params,"enabled")( convertToBoolean)
      directives       <- extractList(params,"directives")( convertListToDirectiveId)
      target           <- convertToRuleTarget(params,"targets")
    } yield {
      RestRule(name, category, shortDescription, longDescription, directives, target.map(Set(_)), enabled)
    }
  }

  def extractRuleCategory (params : Map[String,List[String]]) : Box[RestRuleCategory] = {

    for {
      name        <- extractOneValue(params,"name")(convertToMinimalSizeString(3))
      description <- extractOneValue(params, "description")()
      parent      <- extractOneValue(params,"parent")(convertToRuleCategoryId)
    } yield {
      RestRuleCategory(name, description, parent)
    }
  }

  def extractGroup (params : Map[String,List[String]]) : Box[RestGroup] = {
    for {
      name        <- extractOneValue(params, "displayName")(convertToMinimalSizeString(3))
      description <- extractOneValue(params, "description")()
      enabled     <- extractOneValue(params, "enabled")( convertToBoolean)
      dynamic     <- extractOneValue(params, "dynamic")( convertToBoolean)
      query       <- extractOneValue(params, "query")(convertToQuery)
      _           <- if (query.map(_.criteria.size > 0).getOrElse(true)) Full("Query has at least one criteria") else Failure("Query should containt at least one criteria")
      category    <- extractOneValue(params, "category")(convertToGroupCategoryId)
    } yield {
      RestGroup(name,description,query,dynamic,enabled,category)
    }
  }

  def extractGroupCategory (params : Map[String,List[String]]) : Box[RestGroupCategory] = {

    for {
      name        <- extractOneValue(params,"name")(convertToMinimalSizeString(3))
      description <- extractOneValue(params, "description")()
      parent      <- extractOneValue(params,"parent")(convertToNodeGroupCategoryId)
    } yield {
      RestGroupCategory(name, description, parent)
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

  /*
   * Looking for parameter: "properties=foo=bar"
   * ==> set foo to bar; delete baz, set plop to plop.
   */
  def extractNodeProperties (params : Map[String, List[String]]) : Box[Option[Seq[NodeProperty]]] = {
    extractList(params, "properties") { props =>
      val splitted = props.map { prop =>
        val parts = prop.split('=')
        if(parts.size == 1) NodeProperty(parts(0), "")
        //here, we should parse parts(1) and only fallback to string if not successful.
        else NodeProperty(parts(0), parts(1))
      }
      Full(splitted)
    }
  }

  def extractNode (params : Map[String, List[String]]) : Box[RestNode] = {
    for {
      properties <- extractNodeProperties(params)
      mode       <- extractOneValue(params, "policyMode")(PolicyMode.parseDefault)
    } yield {
      RestNode(properties,mode)
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
        Full(NodeProperty(nameValue, value))
      case (a, b)  =>
        Failure(s"""Error when trying to parse new property: '${compact(render(json))}'. The awaited format is: {"name": string, "value": json}""")
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
      mode       <- extractOneValueJson(json, "policyMode")(PolicyMode.parseDefault)
    } yield {
      RestNode(properties,mode)
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
      case _ => extractDirective(req.params)
    }
  }

  def extractDirective (params : Map[String,List[String]]) : Box[RestDirective] = {
    for {
      name             <- extractOneValue(params, "name")(convertToMinimalSizeString(3)) or extractOneValue(params, "displayName")(convertToMinimalSizeString(3))
      shortDescription <- extractOneValue(params, "shortDescription")()
      longDescription  <- extractOneValue(params, "longDescription")()
      enabled          <- extractOneValue(params, "enabled")( convertToBoolean)
      priority         <- extractOneValue(params, "priority")(convertToInt)
      parameters       <- extractOneValue(params, "parameters")(convertToDirectiveParam)
      techniqueName    <- extractOneValue(params, "techniqueName")(x => Full(TechniqueName(x)))
      techniqueVersion <- extractOneValue(params, "techniqueVersion")(x => Full(TechniqueVersion(x)))
      policyMode       <- extractOneValue(params, "policyMode")(PolicyMode.parseDefault)
    } yield {
      RestDirective(name,shortDescription,longDescription,enabled,parameters,priority, techniqueName, techniqueVersion, policyMode)
    }
  }

  def extractRuleFromJSON (json : JValue) : Box[RestRule] = {
    for {
      name             <- extractOneValueJson(json, "displayName")(convertToMinimalSizeString(3))
      category         <- extractOneValueJson(json, "category")(convertToRuleCategoryId)
      shortDescription <- extractOneValueJson(json, "shortDescription")()
      longDescription  <- extractOneValueJson(json, "longDescription")()
      directives       <- extractJsonListString(json, "directives")(convertListToDirectiveId)
      target           <- convertToRuleTarget(json, "targets")
      enabled          <- extractJsonBoolean(json,"enabled")
    } yield {
      RestRule(name, category, shortDescription, longDescription, directives, target.map(Set(_)), enabled)
    }
  }

  def extractRuleCategory ( json : JValue ) : Box[RestRuleCategory] = {
    for {
      name        <- extractOneValueJson(json,"name")(convertToMinimalSizeString(3))
      description <- extractOneValueJson(json, "description")()
      parent      <- extractOneValueJson(json,"parent")(convertToRuleCategoryId)
    } yield {
      RestRuleCategory(name, description, parent)
    }
  }

  def extractDirectiveFromJSON (json : JValue) : Box[RestDirective] = {
    for {
      name             <- extractOneValueJson(json, "name")(convertToMinimalSizeString(3)) or extractOneValueJson(json, "displayName")(convertToMinimalSizeString(3))
      shortDescription <- extractOneValueJson(json, "shortDescription")()
      longDescription  <- extractOneValueJson(json, "longDescription")()
      enabled          <- extractJsonBoolean(json, "enabled")
      priority         <- extractJsonInt(json,"priority")
      parameters       <- extractJsonDirectiveParam(json)
      techniqueName    <- extractOneValueJson(json, "techniqueName")(x => Full(TechniqueName(x)))
      techniqueVersion <- extractOneValueJson(json, "techniqueVersion")(x => Full(TechniqueVersion(x)))
      policyMode       <- extractOneValueJson(json, "policyMode")(PolicyMode.parseDefault)
    } yield {
      RestDirective(name,shortDescription,longDescription,enabled,parameters,priority,techniqueName,techniqueVersion,policyMode)
    }
  }

  def extractGroupFromJSON (json : JValue) : Box[RestGroup] = {
    for {
      name        <- extractOneValueJson(json, "displayName")(convertToMinimalSizeString(3))
      description <- extractOneValueJson(json, "description")()
      enabled     <- extractJsonBoolean(json, "enabled")
      dynamic     <- extractJsonBoolean(json, "dynamic")
      stringQuery <- queryParser.jsonParse(json \\ "query")
      query       <- queryParser.parse(stringQuery)
      _           <- if (query.criteria.size > 0) Full("Query has at least one criteria") else Failure("Query should containt at least one criteria")
      category    <- extractOneValueJson(json, "category")(convertToGroupCategoryId)
    } yield {
      RestGroup(name,description,Some(query),dynamic,enabled,category)
    }
  }

  def extractGroupCategory ( json : JValue ) : Box[RestGroupCategory] = {
    for {
      name        <- extractOneValueJson(json,"name")(convertToMinimalSizeString(3))
      description <- extractOneValueJson(json, "description")()
      parent      <- extractOneValueJson(json,"parent")(convertToNodeGroupCategoryId)
    } yield {
      RestGroupCategory(name, description, parent)
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
      overridable <- extractJsonBoolean(json, "overridable")
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

  def extractNodeIdsFromJson (json : JValue) : Box[Option[List[NodeId]]] = {
    extractJsonListString(json, "nodeId")(convertListToNodeId)
  }

  def extractNodeStatusFromJson (json : JValue) : Box[NodeStatusAction] = {
    extractOneValueJson(json, "status")(convertToNodeStatusAction) match {
      case Full(Some(status)) => Full(status)
      case Full(None) => Failure("node status should not be empty")
      case eb:EmptyBox => eb ?~ "error with node status"
    }
  }

  def extractNodeDetailLevel (params : Map[String,List[String]]) : Box[NodeDetailLevel] = {
    extractOneValue(params,"include")(convertToNodeDetailLevel) match {
      case Full(Some(level)) => Full(level)
      case Full(None) => Full(DefaultDetailLevel)
      case eb:EmptyBox => eb ?~ "error with node level detail"
    }
  }

  def extractQuery (params : Map[String,List[String]]) : Box[Option[Query]] = {
    extractOneValue(params,"query")(convertToQuery) match {
      case Full(None) =>
        extractOneValue(params,CRITERIA)(convertToQueryCriterion) match {
          case Full(None) => Full(None)
          case Full(Some(Nil)) => Failure("Query should at least contain one criteria")
          case Full(Some(criterion)) =>
            for {
              // Target defaults to NodeReturnType
              optType <- extractOneValue(params,TARGET)(convertToQueryReturnType)
              returnType = optType.getOrElse(NodeReturnType)

              // Composition defaults to None/And
              optComposition <-extractOneValue(params,COMPOSITION)(convertToQueryComposition)
              composition = optComposition.getOrElse(None)

              // Query may fail when parsing
              stringQuery = StringQuery(returnType,composition,criterion.toSeq)
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

  def extractJsonObj[T](key : String)(json : JValue)(jsonValueFun : JObject => Box[T]) : Box[Option[T]]  = {
  json \ key match {
      case obj : JObject => jsonValueFun(obj).map(Some(_))
      case JNothing => Full(None)
      case x => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
    }
  }

  def extractObj[T](key : String)(req : Req)(jsonValueFun : JObject => Box[T]) : Box[Option[T]]  = {
    req.json match {
      case Full(json) => extractJsonObj (key) (json) (jsonValueFun)
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

  def extractDataSource(req : Req, base : DataSource) : Box[DataSource] = {

    def extractDuration (value : String) = {
      tryo { Duration(value) match {
        case a : FiniteDuration => Full(a)
        case _ => Failure(s"${value} is not a valid timeout duration value")
      } }.flatMap(identity)
    }

    def extractDataSourceRunParam(obj : JObject, base : DataSourceRunParameters) = {

      def extractSchedule(obj : JObject, base : DataSourceSchedule) = {

          for {
              scheduleBase <- extractOneValueJson(obj, "type")( _ match {
                case "scheduled" => Full(Scheduled(base.duration))
                case "notscheduled" => Full(NoSchedule(base.duration))
                case _ => Failure("not a valid value for datasource schedule")
              }).map(_.getOrElse(base))
              duration <- extractOneValueJson(obj, "duration")(extractDuration)
          } yield {
            duration match {
              case None => scheduleBase
              case Some(newDuration) =>
                scheduleBase match {
                  case Scheduled(_) => Scheduled(newDuration)
                  case NoSchedule(_) => NoSchedule(newDuration)
                }
            }

        }
      }

      for {
        onGeneration <- extractJsonBoolean(obj, "onGeneration")
        onNewNode    <- extractJsonBoolean(obj, "onNewNode")
        schedule     <- extractJsonObj("schedule")(obj)(extractSchedule(_,base.schedule))
      } yield {
        base.copy(
            schedule.getOrElse(base.schedule)
          , onGeneration.getOrElse(base.onGeneration)
          , onNewNode.getOrElse(base.onNewNode)
        )
      }
    }

    def extractDataSourceType(obj : JObject, base : DataSourceType) = {

      obj \ "name" match {
        case JString(HttpDataSourceType.name) =>
          val httpBase = base match {
            case h : HttpDataSourceType => h
          }

          def extractHttpRequestMode(obj : JObject, base : HttpRequestMode) = {

            obj \ "name" match {
              case JString(OneRequestByNode.name) =>
                Full(OneRequestByNode)
              case JString(OneRequestAllNodes.name) =>
                val allBase = base match {
                  case h : OneRequestAllNodes => h
                  case _ => OneRequestAllNodes("","")
                }
                for {
                  attribute <- extractOneValueJson(obj, "attribute")(boxedIdentity)
                  path <- extractOneValueJson(obj, "path")(boxedIdentity)
                } yield {
                  OneRequestAllNodes(
                      path.getOrElse(allBase.matchingPath)
                    , attribute.getOrElse(allBase.nodeAttribute)
                  )
                }
              case x => Failure(s"Cannot extract request type from: ${x}")
            }
          }

          for {
            url      <- extractOneValueJson(obj, "url")(boxedIdentity)
            path     <- extractOneValueJson(obj, "path")(boxedIdentity)
            method   <- extractOneValueJson(obj, "requestMethod")(boxedIdentity)
            sslCheck <- extractJsonBoolean(obj, "sslCheck")
            timeout  <- extractOneValueJson(obj, "requestTimeout")(extractDuration)
            headers  <- obj \ "headers" match {
              case header@JObject(fields) =>

                sequence(fields.toSeq) { field => extractOneValueJson(header, field.name)( value => Full((field.name,value))).map(_.getOrElse((field.name,""))) }.map(fields => Some(fields.toMap))
              case JNothing => Full(None)
              case _ => Failure("oops")
            }

            requestMode <- extractJsonObj("requestMode")(obj)(extractHttpRequestMode(_,httpBase.requestMode))

          } yield {
            httpBase.copy(
                url.getOrElse(httpBase.url)
              , headers.getOrElse(httpBase.headers)
              , method.getOrElse(httpBase.httpMethod)
              , sslCheck.getOrElse(httpBase.sslCheck)
              , path.getOrElse(httpBase.path)
              , requestMode.getOrElse(httpBase.requestMode)
              , timeout.getOrElse(httpBase.requestTimeOut)
            )
          }

        case x => Failure(s"Cannot extract a data source type from: ${x}")
      }
    }

    for {
      name <- extractString("name")(req) (x => Full(DataSourceName(x)))
      description  <- extractString("description")(req) (boxedIdentity)
      sourceType   <- extractObj("type")(req) (extractDataSourceType(_, base.sourceType))
      runParam     <- extractObj("runParam")(req) (extractDataSourceRunParam(_, base.runParam))
      timeOut      <- extractString("timeout")(req)(extractDuration)
      enabled      <- extractBoolean("enabled")(req)(identity)
    } yield {
      base.copy(
        name = name.getOrElse(base.name)
      , sourceType = sourceType.getOrElse(base.sourceType)
      , description = description.getOrElse(base.description)
      , enabled = enabled.getOrElse(base.enabled)
      , updateTimeOut = timeOut.getOrElse(base.updateTimeOut)
      , runParam = runParam.getOrElse(base.runParam)
    )
    }
  }

  private[this] def boxedIdentity[T] : T => Box[T] = Full(_)
}
