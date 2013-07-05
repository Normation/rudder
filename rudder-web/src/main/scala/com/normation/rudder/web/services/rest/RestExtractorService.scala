package com.normation.rudder.web.services.rest

import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.services.policies.RuleTargetService
import com.normation.rudder.web.rest.rule.RestRule
import net.liftweb.common._
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleTarget
import net.liftweb.json._
import com.normation.rudder.web.rest.directive.RestDirective
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.TechniqueRepository
import scala.util.matching.Regex
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.web.rest.group.RestGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId

case class RestExtractorService (
    readRule             : RoRuleRepository
  , readDirective        : RoDirectiveRepository
  , readGroup            : RoNodeGroupRepository
  , techniqueRepository  : TechniqueRepository
  , targetInfoService    : RuleTargetService
) extends Loggable {


  /*
   * Params Extractors
   */
  private[this] def extractOneValue[T] (params : Map[String,List[String]], key : String)( convertTo : (String,String) => Box[T] = ( (value:String,key:String) => Full(value))) = {
    params.get(key) match {
      case None               => Full(None)
      case Some(value :: Nil) => convertTo(value,key).map(Some(_))
      case _                  => Failure(s"updateRule should contain only one value for $key")
    }
  }

  private[this] def extractList[T] (params : Map[String,List[String]],key : String)( convertTo : (List[String],String) => Box[T] = ( (values:List[String],key:String) => Full(values))) : Box[Option[T]] = {
    params.get(key) match {
      case None       => Full(None)
      case Some(list) => convertTo(list,key).map(Some(_))
    }
  }

  /*
   * JSON extractors
   */
  private[this] def extractOneValueJson[T](json:JValue, key:String )( convertTo : (String,String) => Box[T] = ( (value:String,key:String) => Full(value))) = {
    json \\ key match {
      case JString(value) => convertTo(value,key).map(Some(_))
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


  private[this] def extractJsonList[T] (json : JValue,key : String)( convertTo : (List[String],String) => Box[T] = ( (value:List[String],key:String) => Full(value))) : Box[Option[T]] = {
    json \\ key match {
      case JArray(values) =>
        val list = values.flatMap {
          case JString(value) => Full(value)
          case value          => Failure(s"Not a good value for parameter ${key} and value ${value}")
          }
        convertTo(list,key).map(Some(_))
      case JObject(Nil)   => Full(None)
      case _              => Failure(s"Not a good value for parameter ${key}")
    }
  }

  /*
   * Convert value functions
   */
  private[this] def convertToBoolean (value : String, key : String) : Box[Boolean] = {
    value match {
      case "true"  => Full(true)
      case "false" => Full(false)
      case _       => Failure(s"value for $key should be true or false")
    }
  }

  private[this] def convertToInt (value:String, key:String) : Box[Int] = {
    try {
      Full(value.toInt)
    } catch  {
      case _ : java.lang.NumberFormatException => Failure(s"value for $key should be an integer instead of ${value}")
    }
  }


  private[this] def convertToNodeGroupCategoryId (value:String, key:String) : Box[NodeGroupCategoryId] = {
    readGroup.getGroupCategory(NodeGroupCategoryId(value)).map(_.id) ?~ s"Directive '$value' not found"
  }



  private[this] def convertToDirectiveId (value:String, key:String) : Box[DirectiveId] = {
    readDirective.getDirective(DirectiveId(value)).map(_.id) ?~ s"Directive '$value' not found"
  }

  /*
   * Convert List Functions
   */
  private[this] def convertListToDirectiveId (values : List[String], key : String) : Box[Set[DirectiveId]] = {
    val directives =
      values.filter(_.size != 0).map {
        value => convertToDirectiveId(value,key)
    }

    val failure =
      directives.collectFirst {
        case fail:EmptyBox => fail ?~ "There was an error with a Directive"
      }

    failure match {
      case Some(fail) => fail
      case None => Full(directives.collect{case Full(rt) => rt}.toSet)
    }
  }

  private[this] def convertListToRuleTarget (values : List[String], key : String) : Box[Set[RuleTarget]] = {
    val targets : Set[Box[RuleTarget]] =
      values.map(value => (value,RuleTarget.unser(value)) match {
        case (_,Some(rt)) => Full(rt) // Need to check if the ruletarget is an existing group
        case (wrong,None) => Failure(s"$wrong is not a valid RuleTarget")
      }).toSet

    val failure =
      targets.collectFirst {
        case fail:EmptyBox => fail ?~ "There was an error with a Target"
      }

    failure match {
      case Some(fail) => fail
      case None => Full(targets.collect{case Full(rt) => rt})
    }
  }



  /*
   * Data extraction functions
   */
  def extractPrettify (params : Map[String,List[String]]) : Boolean = {
    extractOneValue(params, "prettify")(convertToBoolean).map(_.getOrElse(false)).getOrElse(false)
  }

  def extractReason (params : Map[String,List[String]]) : Option[String] = {
    extractOneValue(params, "reason")().getOrElse(None)
  }

  def extractChangeRequestName (params : Map[String,List[String]]) : Option[String] = {
    extractOneValue(params, "changeRequestName")().getOrElse(None)
  }

  def extractChangeDescription (params : Map[String,List[String]]) : Option[String] = {
    extractOneValue(params, "changeRequestDescription")().getOrElse(None)
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
          case others => others
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
      name             <- extractOneValue(params,"displayName")()
      shortDescription <- extractOneValue(params,"shortDescription")()
      longDescription  <- extractOneValue(params,"longDescription")()
      enabled          <- extractOneValue(params,"enabled")( convertToBoolean)
      directives       <- extractList(params,"directives")( convertListToDirectiveId)
      targets          <- extractList(params,"ruleTarget")(convertListToRuleTarget)
    } yield {
      RestRule(name,shortDescription,longDescription,directives,targets,enabled)
    }
  }


  def extractGroup (params : Map[String,List[String]]) : Box[RestGroup] = {


    for {
      name        <- extractOneValue(params,"displayName")()
      description <- extractOneValue(params,"description")()
      enabled     <- extractOneValue(params,"enabled")( convertToBoolean)
      dynamic     <- extractOneValue(params,"dynamic")( convertToBoolean)
      query       =  None
    } yield {
      RestGroup(name,description,query,dynamic,enabled)
    }
  }

  def extractDirective (params : Map[String,List[String]]) : Box[RestDirective] = {


    for {
      //technique        <- extractTechnique(params)
      //activeTechnique  <- readDirective.getActiveTechnique(technique.id.name)
      name             <- extractOneValue(params,"displayName")()
      shortDescription <- extractOneValue(params,"shortDescription")()
      longDescription  <- extractOneValue(params,"longDescription")()
      enabled          <- extractOneValue(params,"enabled")( convertToBoolean)
      directives       <- extractList(params,"directives")( convertListToDirectiveId)
      targets          <- extractList(params,"ruleTarget")(convertListToRuleTarget)
      priority         <- extractOneValue(params,"priority")(convertToInt)
      techniqueVersion =  None
    } yield {
      RestDirective(name,shortDescription,longDescription,enabled,None,priority,None)
    }
  }

  def extractRuleFromJSON (json : JValue) : Box[RestRule] = {

    for {
      name <- extractOneValueJson(json, "displayName")()
      shortDescription <- extractOneValueJson(json, "shortDescription")()
      longDescription <- extractOneValueJson(json, "longDescription")()
      directives <- extractJsonList(json, "directives")(convertListToDirectiveId)
      targets <- extractJsonList(json, "directives")(convertListToRuleTarget)
      enabled <- extractJsonBoolean(json,"enabled")
    } yield {
      RestRule(name,shortDescription,longDescription,directives,targets,enabled)
  } }

}