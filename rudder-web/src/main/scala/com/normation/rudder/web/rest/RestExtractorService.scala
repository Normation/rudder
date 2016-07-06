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
        else NodeProperty(parts(0), parts(1))
      }
      Full(splitted)
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
  def extractNodePropertiesrFromJSON (json : JValue) : Box[RestNode] = {
    import net.liftweb.json.JsonParser._
    implicit val formats = DefaultFormats

    Box(json.extractOpt[RestNode]) ?~! "Error when extracting node information"
  }


  def extractDirective (params : Map[String,List[String]]) : Box[RestDirective] = {
    for {
      name             <- extractOneValue(params, "displayName")(convertToMinimalSizeString(3))
      shortDescription <- extractOneValue(params, "shortDescription")()
      longDescription  <- extractOneValue(params, "longDescription")()
      enabled          <- extractOneValue(params, "enabled")( convertToBoolean)
      priority         <- extractOneValue(params, "priority")(convertToInt)
      parameters       <- extractOneValue(params, "parameters")(convertToDirectiveParam)
      techniqueName    <- extractOneValue(params, "techniqueName")(x => Full(TechniqueName(x)))
      techniqueVersion <- extractOneValue(params, "techniqueVersion")(x => Full(TechniqueVersion(x)))
    } yield {
      RestDirective(name,shortDescription,longDescription,enabled,parameters,priority, techniqueName, techniqueVersion)
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
      name             <- extractOneValueJson(json, "displayName")(convertToMinimalSizeString(3))
      shortDescription <- extractOneValueJson(json, "shortDescription")()
      longDescription  <- extractOneValueJson(json, "longDescription")()
      enabled          <- extractJsonBoolean(json, "enabled")
      priority         <- extractOneValueJson(json, "priority")(convertToInt)
      parameters       <- extractJsonDirectiveParam(json)
      techniqueName    <- extractOneValueJson(json, "techniqueName")(x => Full(TechniqueName(x)))
      techniqueVersion <- extractOneValueJson(json, "techniqueVersion")(x => Full(TechniqueVersion(x)))
    } yield {
      RestDirective(name,shortDescription,longDescription,enabled,parameters,priority,techniqueName,techniqueVersion)
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


}
