/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.domain.reports

import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.RuleId
import com.normation.utils.HashcodeCaching
import org.joda.time.DateTime
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides.Unoverridable
import com.normation.rudder.domain.policies.PolicyModeOverrides.Always
import net.liftweb.common.Full
import net.liftweb.common.Failure
import net.liftweb.common.Box
import com.normation.utils.Control.sequence
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceModeName
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.ResolvedAgentRunInterval
import org.joda.time.Duration
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.services.policies.PolicyId
import net.liftweb.common.EmptyBox

final case class NodeModeConfig(
    globalComplianceMode: ComplianceMode
  , nodeHeartbeatPeriod : Option[Int] // if it is defined, then it does override (ie if override = false => None)
  , globalAgentRun      : AgentRunInterval
  , nodeAgentRun        : Option[AgentRunInterval] // if Some and overrides = false, behave like none.
  , globalPolicyMode    : GlobalPolicyMode
  , nodePolicyMode      : Option[PolicyMode]
)

/*
 * A place where we store overriden directive. We keep what rule->directive
 * is overriden by what other rule->directive
 */
case class OverridenPolicy(
    policy     : PolicyId
  , overridenBy: PolicyId
)

final case class NodeExpectedReports(
    nodeId             : NodeId
  , nodeConfigId       : NodeConfigId
  , beginDate          : DateTime
  , endDate            : Option[DateTime]
  , modes              : NodeModeConfig
  , ruleExpectedReports: List[RuleExpectedReports]
  , overrides          : List[OverridenPolicy]
) {

  def configInfo = NodeConfigIdInfo(nodeConfigId, beginDate, endDate)

  //for now, nodes don't override compliance mode
  def complianceMode = modes.globalComplianceMode

  def policyMode = PolicyMode.computeMode(modes.globalPolicyMode, modes.nodePolicyMode)

  def agentRun: ResolvedAgentRunInterval = {
    if (nodeId == Constants.ROOT_POLICY_SERVER_ID) {
      //special case. The root policy server always run each 5 minutes
      ResolvedAgentRunInterval(Duration.standardMinutes(5), 1)
    } else {
      val run: Int = modes.nodeAgentRun.flatMap(x =>
          if (x.overrides.getOrElse(false)) Some(x.interval) else None
      ).getOrElse(modes.globalAgentRun.interval)

      val heartbeat = modes.nodeHeartbeatPeriod.getOrElse(modes.globalComplianceMode.heartbeatPeriod)

      ResolvedAgentRunInterval(Duration.standardMinutes(run.toLong), heartbeat)
    }
  }
}

final case class RuleExpectedReports(
    ruleId    : RuleId
  , directives: List[DirectiveExpectedReports]
)

/**
 * A Directive may have several components
 */
final case class DirectiveExpectedReports (
    directiveId: DirectiveId
  , policyMode : Option[PolicyMode]
  , isSystem   : Boolean
  , components : List[ComponentExpectedReport]
) extends HashcodeCaching

/**
 * The Cardinality is per Component
 */
case class ComponentExpectedReport(
    componentName             : String

  //TODO: change that to have a Seq[(String, String).
  //or even better, un Seq[ExpectedValue] where expectedValue is the pair
  , componentsValues          : List[String]
  , unexpandedComponentsValues: List[String]
) extends HashcodeCaching {

  /**
   * Get a normalized list of pair of (value, unexpandedvalue).
   * We have three case to consider:
   * - both source list have the same size => easy, just zip them
   * - the unexpandedvalues is empty: it may happen due to old version of
   *   rudder not having recorded them => too bad, use the expanded value
   *   in both case
   * - different size: why on hell do we have a data model authorizing that
   *   and the TODO is not addressed ?
   *   In that case, there is no good solution. We choose to:
   *   - remove unexpanded values if it's the longer list
   *   - complete unexpanded values with matching values in the other case.
   */
  def groupedComponentValues : Seq[(String, String)] = {
    if (componentsValues.size <= unexpandedComponentsValues.size) {
      componentsValues.zip(unexpandedComponentsValues)
    } else { // strictly more values than unexpanded
      val n = unexpandedComponentsValues.size
      val unmatchedValues = componentsValues.drop(n)
      componentsValues.take(n).zip(unexpandedComponentsValues) ++ unmatchedValues.zip(unmatchedValues)
    }
  }
}

final case class NodeConfigId(value: String)

final case class NodeAndConfigId(
    nodeId : NodeId
  , version: NodeConfigId
)

final case class NodeConfigVersions(
     nodeId  : NodeId
     //the most recent version is the head
     //and the list can be empty
   , versions: List[NodeConfigId]
 )


final case class NodeConfigIdInfo(
    configId : NodeConfigId
  , creation : DateTime
  , endOfLife: Option[DateTime]
)

object ExpectedReportsSerialisation {
  import net.liftweb.json._
  import net.liftweb.json.JsonDSL._


  /*
   * This object will be used for the JSON serialisation
   * to / from database
   */
  final case class JsonNodeExpectedReports(
      modes              : NodeModeConfig
    , ruleExpectedReports: List[RuleExpectedReports]
    , overrides          : List[OverridenPolicy]
  )


  def jsonNodeExpectedReports(n: JsonNodeExpectedReports): JValue = {
    (
        ( "modes" ->
          ( "globalPolicyMode"       -> (
              ("mode"                  -> n.modes.globalPolicyMode.mode.name)
            ~ ("overridable"           -> (n.modes.globalPolicyMode.overridable match {
                                            case Unoverridable => false
                                            case Always        => true
                                          })
              )
          ) )
        ~ ( "nodePolicyMode"         -> n.modes.nodePolicyMode.map(_.name) )
        ~ ( "globalComplianceMode"   -> n.modes.globalComplianceMode.name )
        ~ ( "globalHeartbeatPeriod"  -> n.modes.globalComplianceMode.heartbeatPeriod )
        ~ ( "nodeHeartbeatPeriod"    -> n.modes.nodeHeartbeatPeriod )
        ~ ( "globalAgentRunInterval" ->
                   (
                       ( "interval"    -> n.modes.globalAgentRun.interval )
                     ~ ( "startMinute" -> n.modes.globalAgentRun.startMinute )
                     ~ ( "splayHour"   -> n.modes.globalAgentRun.startHour )
                     ~ ( "splaytime"   -> n.modes.globalAgentRun.splaytime )
                   )
          )
        ~ ( "nodeAgentRunInterval"   -> (n.modes.nodeAgentRun.flatMap { run =>
            if(run.overrides.getOrElse(false)) {
                   Some(
                       ( "interval"    -> run.interval )
                     ~ ( "startMinute" -> run.startMinute )
                     ~ ( "splayHour"   -> run.startHour )
                     ~ ( "splaytime"   -> run.splaytime )
                   )
            } else {
              None
            }
          } ) )
        )
     ~  ("rules" -> jsonRuleExpectedReports(n.ruleExpectedReports))
     ~  ("overrides" -> (n.overrides.map { o =>
          (
            ("policy"      -> ( ("ruleId" -> o.policy.ruleId.value     ) ~ ("directiveId" -> o.policy.directiveId.value) ))
          ~ ("overridenBy" -> ( ("ruleId" -> o.overridenBy.ruleId.value) ~ ("directiveId" -> o.overridenBy.directiveId.value) ))
          )
        }))
    )
  }

  def jsonRuleExpectedReports(rules: List[RuleExpectedReports]): JArray = {
    (
      rules.map { r =>
         (
           ("ruleId"     -> r.ruleId.value)
         ~ ("directives" -> (r.directives.map { d =>
             (
               ("directiveId" -> d.directiveId.value)
             ~ ("policyMode"  -> d.policyMode.map( _.name))
             ~ ("isSystem"    -> d.isSystem )
             ~ ("components"  -> (d.components.map { c =>
                 (
                   ("componentName" -> c.componentName)
                 ~ ("values"        -> c.componentsValues)
                 ~ ("unexpanded"    -> c.unexpandedComponentsValues)
                 )
               }))
             )
           }))
         )
      }
    )
  }

  def parseJsonNodeExpectedReports(s: String): Box[JsonNodeExpectedReports] = {
    import PolicyMode.{Audit, Enforce}
    import net.liftweb.util.Helpers.tryo

    implicit val formats = DefaultFormats

    implicit class ToValidInt(b: BigInt) {
      def toValidInt = if(b.isValidInt) b.toInt else throw new NumberFormatException(s"${b.toString} is not a valid integer")
    }

    def modes(json: JValue): Box[NodeModeConfig] = {
      def fail = Failure(s"Cannot parse JSON as modes parameters for expected node configuration: ${compactRender(json)}")
      (
          complianceMode( json \ "globalComplianceMode")
        , json \ "globalHeartbeatPeriod"
        , agentRun( json \ "globalAgentRunInterval", None)
        , policyMode( json \ "globalPolicyMode" \ "mode")
        , json \ "globalPolicyMode" \ "overridable"
        , policyMode(json \ "nodePolicyMode")
      ) match {
        case (Some(gcm), JInt(ghp), Some(gari), Some(gpm), JBool(gpo), npm) =>
          try {
            Full(NodeModeConfig(
                GlobalComplianceMode(gcm, ghp.toValidInt)
              , heartbeat( json \ "nodeHeartbeatPeriod")
              , gari
              , agentRun( json \ "nodeAgentRunInterval", Some(true) )
              , GlobalPolicyMode(gpm, if(gpo) Always else Unoverridable)
              , npm
            ))
          } catch {
            case ex: NumberFormatException => fail
          }
        case _ => fail
      }
    }

    def heartbeat(json: JValue) = json match {
      case JInt(i) => try {
          Some(i.toValidInt)
        } catch {
          case ex: NumberFormatException => None
        }
      case _ => None
    }

    def policyMode(json: JValue): Option[PolicyMode] = json match {
      case JString(Audit.name)   => Some(Audit)
      case JString(Enforce.name) => Some(Enforce)
      case _ => None
    }

    def complianceMode(json: JValue): Option[ComplianceModeName] = json match {
      case JString(name)  => ComplianceModeName.parse(name).toOption
      case _              => None
    }

    def agentRun(json: JValue, overrides: Option[Boolean]): Option[AgentRunInterval] = {
      (
          json \ "interval"
        , json \ "startMinute"
        , json \ "splayHour"
        , json \ "splaytime"
      ) match {
        case (JInt(i), JInt(sm), JInt(sph), JInt(spt)) =>
          try {
            Some(AgentRunInterval(overrides, i.toValidInt, sm.toValidInt, spt.toValidInt, spt.toValidInt))
          } catch {
            case ex: NumberFormatException => None
          }
        case _ => None
      }
    }

    def over(json: JValue): Box[OverridenPolicy] = {
      (
          (json \ "policy" \ "ruleId")
        , (json \ "policy" \ "directiveId")
        , (json \ "overridenBy" \ "ruleId")
        , (json \ "overridenBy" \ "directiveId")
     ) match {
        case (JString(drid), JString(ddid), JString(orid), JString(odid)) =>
          val (v1, v2) = {
            ((json \ "policy" \ "techniqueVersion"), (json \ "overridenBy" \ "techniqueVersion")) match {
              case (JString(v1), JString(v2)) => (TechniqueVersion(v1), TechniqueVersion(v2))
              case _                          => (TechniqueVersion("0.0"), TechniqueVersion("0.0"))
            }
          }

          Full(OverridenPolicy(PolicyId(RuleId(drid), DirectiveId(ddid), v1), PolicyId(RuleId(orid), DirectiveId(odid), v2)))
        case _ =>
          Failure(s"Error when parsing rule expected reports from json: '${compactRender(json)}'")
      }
    }

    def rule(json: JValue): Box[RuleExpectedReports] = {
      (
          (json \ "ruleId" )
        , (json \ "directives")
     ) match {
        case (JString(id), jsonDirectives) =>
          for {
            directives <- jsonDirectives match {
                            case JArray(directives) => sequence(directives)(directive)
                            case x                  => Failure(s"Error when parsing the list of directives from expected rule report: '${compactRender(x)}'")
                          }
          } yield {
            RuleExpectedReports(RuleId(id), directives.toList)
          }
        case _ =>
          Failure(s"Error when parsing rule expected reports from json: '${compactRender(json)}'")
      }
    }

    def directive(json: JValue): Box[DirectiveExpectedReports] = {
      (
          (json \ "directiveId" )
        , (json \ "policyMode"  )
        , (json \ "components"  )
     ) match {
        case (JString(id), jsonMode, jsonComponents ) =>
          for {
            components <- jsonComponents match {
                            case JArray(components) => sequence(components)(component)
                            case x                  => Failure(s"Error when parsing the list of components from expected directive report: '${compactRender(x)}'")
                          }
          } yield {
            //if isSystem is not defined => false
            val isSystem = (json \ "isSystem"  ) match {
              case JBool(true) => true
              case _           => false
            }

            DirectiveExpectedReports(DirectiveId(id), policyMode(jsonMode), isSystem, components.toList)
          }
        case _ =>
          Failure(s"Error when parsing directive expected reports from json: '${compactRender(json)}'")
      }
    }


    def component(json: JValue): Box[ComponentExpectedReport] = {
      (
          (json \ "componentName" )
        // , (json \ "cardinality") // #10625: ignore cardinality
        , (json \ "values").extractOpt[List[String]]
        , (json \ "unexpanded").extractOpt[List[String]]
     ) match {
        case (JString(name), Some(values), Some(unexpanded) ) =>
          tryo(ComponentExpectedReport(name, values, unexpanded))
        case _ =>
          Failure(s"Error when parsing component expected reports from json: '${compactRender(json)}'")
      }
    }

    val overrideUnserErrorMsg = s"Error when deserializing policy overrides in node configuration. They will be ignore. A full policy regeneration should correct the problem."

    for {
      json  <- tryo { parse(s) }
      modes <- (json \ "modes" ) match {
                 case JNothing => Failure(s"Error, missing mandatory 'modes' definition when parsing expected node configuration: '${compactRender(json)}'")
                 case x        => modes(x)
               }
      rules <- (json \ "rules") match {
                 case JArray(rules) => sequence(rules)(rule)
                 case x             => Failure(s"Error when parsing the list of rules from expected node configuration: '${compactRender(x)}'")
               }
      over  <- (json \ "overrides") match {
                 //we don't want to fail nodeconfig etraction for that.
                 case JArray(overrides) => sequence(overrides)(over) match {
                   case Full(seq)    => Full(seq)
                   case eb: EmptyBox =>
                     val msg = eb ?~! overrideUnserErrorMsg
                     ApplicationLogger.error(msg.messageChain)
                     Full(Nil)
                 }
                 case x                 =>
                     ApplicationLogger.error(overrideUnserErrorMsg)
                     Full(Nil)
               }
    } yield {
      JsonNodeExpectedReports(modes, rules.toList, over.toList)
    }
  }

  implicit class NodeToJson(n: NodeExpectedReports) {
    def toJValue() = {
      jsonNodeExpectedReports(JsonNodeExpectedReports(n.modes, n.ruleExpectedReports, n.overrides))
    }
    def toJson() = prettyRender(toJValue)
    def toCompactJson = compactRender(toJValue)
  }
}


object NodeConfigIdSerializer {

  import net.liftweb.json._
  import org.joda.time.format.ISODateTimeFormat

  //date are ISO format
  private[this] val isoDateTime = ISODateTimeFormat.dateTime

  /*
   * In the database, we only keep creation time.
   * Interval are build with the previous/next.
   *
   * The format is :
   * { "configId1":"creationDate1", "configId2":"creationDate2", ... }
   */

  def serialize(ids: Vector[NodeConfigIdInfo]) : String = {
    import net.liftweb.json.JsonDSL._

    //be careful, we can have several time the same id with different creation date
    //we want an array of { begin : id }
    val m: JValue = JArray(ids.toList.sortBy(_.creation.getMillis).map { case NodeConfigIdInfo(NodeConfigId(id), creation, _) =>
      (creation.toString(isoDateTime) -> id):JObject
    })

    compactRender(m)
  }

  /*
   * from a JSON object: { "id1":"date1", "id2":"date2", ...}, get the list of
   * components values Ids.
   * May return an empty object
   */
  def unserialize(ids:String) : Vector[NodeConfigIdInfo] = {

    if(null == ids || ids.trim == "") Vector()
    else {
      implicit val formats = DefaultFormats
      val configs = parse(ids).extractOrElse[List[Map[String, String]]](List()).flatMap { case map =>
        try {
          Some(map.map { case (date, id) => (NodeConfigId(id), isoDateTime.parseDateTime(date))})
        } catch {
          case e:Exception => None
        }
      }.flatten.sortBy( _._2.getMillis )

      //build interval
      configs match {
        case Nil    => Vector()
        case x::Nil => Vector(NodeConfigIdInfo(x._1, x._2, None))
        case t      => t.sliding(2).map {
            //we know the size of the list is 2
            case _::Nil | Nil => throw new IllegalArgumentException("An impossible state was reached, please contact the dev about it!")
            case x::y::t      => NodeConfigIdInfo(x._1, x._2, Some(y._2))
          }.toVector :+ {
            val x = t.last
            NodeConfigIdInfo(x._1, x._2, None)
          }
      }
    }
 }
}

