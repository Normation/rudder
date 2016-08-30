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

package com.normation.rudder.repository.jdbc

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{ Failure => TFailure }
import scala.util.{ Success => TSuccess }

import com.normation.cfclerk.domain.Technique
import com.normation.rudder.db.DB
import com.normation.rudder.db.SlickSchema
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.repository.HistorizationRepository

import org.joda.time.DateTime
import net.liftweb.common._

class HistorizationJdbcRepository(schema: SlickSchema)  extends HistorizationRepository with  Loggable {

  import schema.api._

  def getAllOpenedNodes() : Future[Seq[DB.SerializedNodes]] = {
    val action = Compiled(schema.serializedNodes.filter(_.endTime.isEmpty)).result
    schema.db.run(action)
  }

  def updateNodes(nodes: Seq[NodeInfo], closable: Seq[String]): Future[Unit] = {
    val toClose = nodes.map(x => x.id.value) ++ closable
    val q = for {
      e <- schema.serializedNodes
           if( e.endTime.isEmpty && (e.nodeId inSet(toClose)) )
    } yield {
      e.endTime
    }

    val action = (for {
      updated  <- q.update(Some(DateTime.now))
      inserted <- (schema.serializedNodes ++= nodes.map(DB.Historize.fromNode))
    } yield {
      ()
    }).transactionally
    schema.db.run(action)
  }

  /**
   * Fetch the serialization of groups that are still open (endTime is null), along with the
   * nodes within
   */
  def getAllOpenedGroups() : Future[Seq[(DB.SerializedGroups, Seq[DB.SerializedGroupsNodes])]] = {

    def nodeJoin(gids: Traversable[Long]) = for {
      join <- schema.serializedGroupsNodes
              if(join.groupPkeyId inSet(gids))
    } yield {
      join
    }

    val groups = for {
       group <- schema.serializedGroups
                if(group.endTime.isEmpty)
    } yield {
      group
    }

    val action = for {
      gs   <- groups.result
      join <- nodeJoin(gs.map(_.id.get)).result
    } yield {
      (gs, join)
    }

    schema.db.run(action.asTry).map {
      case TSuccess((groups, joins)) =>
        val joinMap = joins.groupBy(_.groupPkeyId)
        groups.map(g => (g, joinMap.getOrElse(g.id.get, Seq())))
      case TFailure(ex) => throw ex
    }
  }


  def updateGroups(groups : Seq[NodeGroup], closable : Seq[String]): Future[Unit] = {
    val toClose = groups.map(x => x.id.value) ++ closable
    val q = for {
      e <- schema.serializedGroups
           if( e.endTime.isEmpty && (e.groupId inSet(toClose)) )
    } yield {
      e.endTime
    }

    val action = (for {
      updated  <- q.update(Some(DateTime.now))
      inserted <- (schema.serializedGroups ++= groups.map(DB.Historize.fromNodeGroup))
    } yield {
      ()
    }).transactionally
    schema.db.run(action)
  }


  def getAllOpenedDirectives() : Future[Seq[DB.SerializedDirectives]] = {
    val action = Compiled(schema.serializedDirectives.filter(_.endTime.isEmpty)).result
    schema.db.run(action)
  }

  def updateDirectives(
      directives : Seq[(Directive, ActiveTechnique, Technique)]
    , closable : Seq[String]
  ): Future[Unit] = {
    val toClose = directives.map(x => x._1.id.value) ++ closable
    val q = for {
      e <- schema.serializedDirectives
           if( e.endTime.isEmpty && (e.directiveId inSet(toClose)) )
    } yield {
      e.endTime
    }

    val action = (for {
      updated  <- q.update(Some(DateTime.now))
      inserted <- (schema.serializedDirectives ++= directives.map(DB.Historize.fromDirective))
    } yield {
      ()
    }).transactionally
    schema.db.run(action)
  }

  def getAllOpenedRules() : Future[Seq[Rule]] = {
    val ruleQuery = Compiled(schema.serializedRules.filter(_.endTime.isEmpty))

    def directiveQuery(rules: Seq[DB.SerializedRules]) = {
      for {
        directives <- schema.serializedRuleDirectives
                      if( directives.rulePkeyId inSet( rules.map( _.id.get ) ) )
      } yield {
        directives
      }
    }

    def groupQuery(rules: Seq[DB.SerializedRules]) = {
      for {
        groups     <- schema.serializedRuleGroups
                      if( groups.rulePkeyId inSet( rules.map( _.id.get ) ) )
      } yield {
        groups
      }
    }


    val action = for {
      rules      <- ruleQuery.result
      result     <- for {
                      directives <- directiveQuery(rules).result
                      groups     <- groupQuery(rules).result
                    } yield {
                      (directives, groups)
                    }
    } yield {
      (rules, result._1, result._2)
    }

    schema.db.run(action).map { case (rules, directives, groups) =>
        val dMap = directives.groupBy(_.rulePkeyId)
        val gMap = groups.groupBy(_.rulePkeyId)
        rules.map( rule => DB.Historize.fromSerializedRule(
            rule
          , gMap.getOrElse(rule.id.get, Seq())
          , dMap.getOrElse(rule.id.get, Seq())
        ) )
    }
  }


  def updateRules(rules : Seq[Rule], closable : Seq[String]) : Future[Unit] = {
    val toClose = rules.map(x => x.id.value) ++ closable
    val q = for {
      e <- schema.serializedRules
           if( e.endTime.isEmpty && (e.ruleId inSet(toClose)) )
    } yield {
      e.endTime
    }

    def oneRule(rule: Rule) = {
      for {
        id <- ((schema.serializedRules returning schema.serializedRules.map(_.id)) += DB.Historize.fromRule(rule))
        _  <- DBIO.seq((rule.directiveIds.toSeq.map(d => schema.serializedRuleDirectives += DB.SerializedRuleDirectives(id, d.value)) ++
                        rule.targets.toSeq.map(t => schema.serializedRuleGroups += DB.SerializedRuleGroups(id, t.target))):_*)
        } yield {
          ()
        }
    }

    val action = (for {
      _ <- q.update(Some(DateTime.now))
      _ <- DBIO.seq(rules.map(oneRule):_*)
    } yield {
      ()
    }).transactionally

    schema.db.run(action)
  }

  def getOpenedGlobalSchedule() : Future[Option[DB.SerializedGlobalSchedule]] = {
    val action = Compiled(schema.serializedGlobalSchedule.filter(_.endTime.isEmpty)).result.headOption
    schema.db.run(action)
  }

  def updateGlobalSchedule(
        interval    : Int
      , splaytime   : Int
      , start_hour  : Int
      , start_minute: Int
  ) : Future[Unit] = {
    val q = for {
      e <- schema.serializedGlobalSchedule
           if( e.endTime.isEmpty )
    } yield {
      e.endTime
    }

    val action = (for {
      updated  <- q.update(Some(DateTime.now))
      inserted <- (schema.serializedGlobalSchedule += DB.Historize.fromGlobalSchedule(interval, splaytime, start_hour, start_minute))
    } yield {
      ()
    }).transactionally
    schema.db.run(action)
  }
}
