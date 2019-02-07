/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.db

import com.normation.cfclerk.domain.Technique
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.reports.execution.{ AgentRun => RudderAgentRun }
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.repository.GitCommitId
import com.normation.rudder.rule.category.RuleCategoryId

import org.joda.time.DateTime

import doobie._
import com.normation.rudder.db.Doobie._
import cats.implicits._





/*
 * Here, we are declaring case classes that are mapped to SQL tables.
 * We don't have to mappe ALL table, just the one we are using, and for
 * which it is more convenient to have a case class than a simple tuple
 * or ah HList.
 *
 * Convention: all case classes declared here are under the DB object
 * and should always be used with that prefix, for ex:
 *
 * DB.ExpectedReports
 *
 * (i.e, we should never do "import _"). So that we are able to differenciate
 * from ExpectedReports (Rudder object) to DB.ExpectedReports (a line from
 * the table).
 */

final object DB {

  //////////

  final case class MigrationEventLog[T](
      id                 : T
    , detectionTime      : DateTime
    , detectedFileFormat : Long
    , migrationStartTime : Option[DateTime]
    , migrationEndTime   : Option[DateTime]
    , migrationFileFormat: Option[Long]
    , description        : Option[String]
  )



  final case class Reports[T](
      id                 : T
    , executionDate      : DateTime
    , nodeId             : String
    , directiveId        : String
    , ruleId             : String
    , serial             : Int
    , component          : String
    , keyValue           : String
    , executionTimestamp : DateTime
    , eventType          : String
    , policy             : String
    , msg                : String
  )

  def insertReports(reports: List[com.normation.rudder.domain.reports.Reports]): ConnectionIO[Int] = {
    val dbreports = reports.map { r =>
      DB.Reports[Unit]((), r.executionDate, r.nodeId.value, r.directiveId.value, r.ruleId.value, r.serial
                      , r.component, r.keyValue, r.executionTimestamp, r.severity, "policy", r.message)
    }

    Update[DB.Reports[Unit]]("""
      insert into ruddersysevents
        (executiondate, nodeid, directiveid, ruleid, serial, component, keyvalue, executiontimestamp, eventtype, policy, msg)
      values (?,?,?, ?,?,?, ?,?,?, ?,?)
    """).updateMany(dbreports)
  }
  //////////

  final case class GitCommitJoin (
      gitCommit     : GitCommitId
    , modificationId: ModificationId
  )

  //////////

  final case class RunProperties (
      name : String
    , value: String
  )

  //////////

  final case class AgentRun(
      nodeId      : String
    , date        : DateTime
    , nodeConfigId: Option[String]
    , isCompleted : Boolean
    , insertionId : Long
  ) {
    def toAgentRun = RudderAgentRun(AgentRunId(NodeId(nodeId), date), nodeConfigId.map(NodeConfigId), isCompleted, insertionId)
  }

  //////////

  case class StatusUpdate(
      key    : String
    , lastId : Long
    , date   : DateTime
  )

  //////////

  final  case class SerializedGroups[T](
      id              : T
    , groupId         : String
    , groupName       : String
    , groupDescription: Option[String]
    , nodeCount       : Int
    , groupStatus     : Int
    , startTime       : DateTime
    , endTime         : Option[DateTime]
  )

  //////////

  case class SerializedGroupsNodes(
      groupPkeyId: Long   // really, the database id from the group
    , nodes      : String
  )

  //////////

  case class SerializedNodes[T](
        id             : T  // will be Unit for insert and Long for select
      , nodeId         : String
      , nodeName       : String
      , nodeDescription: Option[String]
      , startTime      : DateTime
      , endTime        : Option[DateTime]
  )

  //////////

  case class SerializedDirectives[T](
        id                  : T
      , directiveId         : String
      , directiveName       : String
      , directiveDescription: Option[String]
      , priority            : Int
      , techniqueName       : String
      , techniqueHumanName  : String
      , techniqueDescription: Option[String]
      , techniqueVersion    : String
      , startTime           : DateTime
      , endTime             : Option[DateTime]
  )

  //////////

  case class SerializedRules[T](
      id               : T
    , ruleId           : String
    , categoryId       : Option[String]
    , name             : String
    , shortDescription : Option[String]
    , longDescription  : Option[String]
    , isEnabledStatus  : Boolean
    , startTime        : DateTime
    , endTime          : Option[DateTime]
  )

  //////////

  final case class SerializedRuleGroups(
      rulePkeyId: Long   // really, the database id from the group
    , targetSerialisation      : String
  )

  //////////

  final case class SerializedRuleDirectives(
      rulePkeyId  : Long   // really, the database id from the group
    , directiveId : String
  )

  //////////

  final case class SerializedGlobalSchedule[T](
      id          : T //long or unit
    , interval    : Int
    , splaytime   : Int
    , start_hour  : Int
    , start_minute: Int
    , startTime   : DateTime
    , endTime     : Option[DateTime]
  )

  object Historize {

    //sanitize a string to option string
    def Opt(s: String) = s match {
      case null => None
      case _ if(s.trim == "") => None
      case _ => Some(s)
    }

    // Utility method to convert from/to nodeGroup/SerializedGroups
    def fromNodeGroup(nodeGroup : NodeGroup) : SerializedGroups[Unit] = {
      SerializedGroups((), nodeGroup.id.value,
              nodeGroup.name,
              Opt(nodeGroup.description),
              nodeGroup.serverList.size,
              isDynamicToSql(nodeGroup.isDynamic),
              DateTime.now(), None )
    }

    def isDynamicToSql(boolean : Boolean) : Int = {
      boolean match {
        case true => 1;
        case false => 0;
      }
    }

    def fromSQLtoDynamic(value : Int) : Option[Boolean] = {
      value match {
        case 1 => Some(true)
        case 0 => Some(false)
        case _ => None
      }
    }

    def fromNode(node : NodeInfo) : SerializedNodes[Unit] = {
      new SerializedNodes((), node.id.value,
              node.hostname,
              Opt(node.description),
              DateTime.now(), None )
    }

    def fromDirective(t3 : (Directive, ActiveTechnique, Technique)) : SerializedDirectives[Unit] = {
      val (directive, at, technique) = t3
      SerializedDirectives[Unit](
        (), directive.id.value,
              directive.name,
              Opt(directive.shortDescription),
              directive.priority,
              at.techniqueName.value,
              technique.name,
              Opt(technique.description),
              directive.techniqueVersion.toString,
              DateTime.now(), None )
    }

    def fromSerializedRule(
        rule : SerializedRules[Long]
      , ruleTargets : Seq[SerializedRuleGroups]
      , directives : Seq[SerializedRuleDirectives]
    ) : Rule = {
      Rule (
          RuleId(rule.ruleId)
        , rule.name
        , RuleCategoryId(rule.categoryId.getOrElse("rootRuleCategory")) // this is not really useful as RuleCategory are not really serialized
        , ruleTargets.flatMap(x => RuleTarget.unser(x.targetSerialisation)).toSet
        , directives.map(x => DirectiveId(x.directiveId)).toSet
        , rule.shortDescription.getOrElse("")
        , rule.longDescription.getOrElse("")
        , rule.isEnabledStatus
        , false
      )

    }

    def fromRule(rule : Rule) : SerializedRules[Unit] = {
      SerializedRules (
          ()
        , rule.id.value
        , Opt(rule.categoryId.value)
        , rule.name
        , Opt(rule.shortDescription)
        , Opt(rule.longDescription)
        , rule.isEnabledStatus
        , DateTime.now()
        , None
      )
    }

    def fromGlobalSchedule(
          interval    : Int
        , splaytime   : Int
        , start_hour  : Int
        , start_minute: Int) : SerializedGlobalSchedule[Unit] = {
      SerializedGlobalSchedule(
              ()
            , interval
            , splaytime
            , start_hour
            , start_minute
            , DateTime.now()
            , None
      )
    }
  }

}


