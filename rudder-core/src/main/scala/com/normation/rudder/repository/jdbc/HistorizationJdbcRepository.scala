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

import net.liftweb.common._

import com.normation.rudder.db.DB
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.SlickSchema
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.repository.HistorizationRepository

import doobie.imports._
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import org.joda.time.DateTime
import com.normation.cfclerk.domain.Technique
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.Rule

class HistorizationJdbcRepository(schema: SlickSchema)  extends HistorizationRepository with  Loggable {

  val doobie = new Doobie(schema.datasource)
  import doobie._

  def getAllOpenedNodes(): Seq[DB.SerializedNodes[Long]] = {
    sql"select * from nodes where endtime is null".query[DB.SerializedNodes[Long]].list.transact(xa).run
  }

  def updateNodes(nodes: Seq[NodeInfo], closable: Seq[String]): Unit = {
    (nodes.map(x => x.id.value).toList ++ closable).toNel.map { toClose =>
      implicit val closeParam = Param.many(toClose)

      (for {
        updated  <- sql"update nodes set endtime = ${Some(DateTime.now)} where endtime is null and nodeid in (${toClose: toClose.type})".update.run
        inserted <- Update[DB.SerializedNodes[Unit]](
                      "insert into nodes (nodeid, nodename, nodedescription, starttime, endtime) values (?, ?, ?, ?, ?)"
                    ).updateMany(nodes.map(DB.Historize.fromNode).toList)
      } yield {
        ()
      }).transact(xa).run
    }
  }

  /**
   * Fetch the serialization of groups that are still open (endTime is null), along with the
   * nodes within
   */
  def getAllOpenedGroups(): Seq[(DB.SerializedGroups[Long], Seq[DB.SerializedGroupsNodes])] = {
//    implicit object monoid extends MonoidConnectionIOList[DB.SerializedGroupsNodes]

    val action = for {
      groups     <- sql"select * from groups where endtime is null".query[DB.SerializedGroups[Long]].list
      joinGroups <- groups.map( _.id).toNel.foldMap { joinIds =>
                      implicit val joinIdsParam = Param.many(joinIds)
                      sql"select * from groupsnodesjoin where grouppkeyid in (${joinIds: joinIds.type})".query[DB.SerializedGroupsNodes].list
                    }(Monoid.liftMonoid)
    } yield {
      val byIds = joinGroups.groupBy(_.groupPkeyId)
      groups.map(x => (x, byIds.getOrElse(x.id, Nil)))
    }

    action.transact(xa).run
  }


  def updateGroups(groups : Seq[NodeGroup], closable : Seq[String]): Unit = {
    (groups.map(x => x.id.value).toList ++ closable).toNel.map { toClose =>
      implicit val closeParam = Param.many(toClose)

      (for {
        updated  <- sql"update groups set endtime = ${Some(DateTime.now)} where endtime is null and groupid in (${toClose: toClose.type})".update.run
        inserted <- Update[DB.SerializedGroups[Unit]](
                      "insert into groups (groupid, groupname, groupdescription, nodecount, groupstatus, starttime, endtime) values (?, ?, ?, ?, ?, ?, ?)"
                    ).updateMany(groups.map(DB.Historize.fromNodeGroup).toList)
      } yield {
        ()
      }).transact(xa).run
    }
  }

  def getAllOpenedDirectives(): Seq[DB.SerializedDirectives[Long]] = {
    sql"select * from DIRECTIVES where endtime is null".query[DB.SerializedDirectives[Long]].list.transact(xa).run
  }

  def updateDirectives(
      directives : Seq[(Directive, ActiveTechnique, Technique)]
    , closable : Seq[String]
  ): Unit = {
    (directives.map(x => x._1.id.value).toList ++ closable).toNel.map { toClose =>
      implicit val closeParam = Param.many(toClose)

      (for {
        updated  <- sql"update directives set endtime = ${Some(DateTime.now)} where endtime is null and directiveid in (${toClose: toClose.type})".update.run
        inserted <- Update[DB.SerializedDirectives[Unit]](
                      "insert into directives (directiveid, directivename, directivedescription, priority, techniquename, "+
                      "techniquedescription, techniqueversion, starttime, endtime) values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    ).updateMany(directives.map(DB.Historize.fromDirective).toList)
      } yield {
        ()
      }).transact(xa).run
    }
  }

  def getAllOpenedRules() : Seq[Rule] = {
    (for {
      rules      <- sql"select * from rules where endtime is null".query[DB.SerializedRules[Long]].list
      ruleIds    =  rules.map( _.id).toNel
      groups     <- ruleIds.foldMap { joinIds =>
                        implicit val joinIdsParam = Param.many(joinIds)
                        sql"""select rulepkeyid, targetserialisation
                              from rulesgroupjoin
                              where rulepkeyid in (${joinIds: joinIds.type})""".query[DB.SerializedRuleGroups].list
                    }(Monoid.liftMonoid)
      directives <- ruleIds.foldMap { joinIds =>
                        implicit val joinIdsParam = Param.many(joinIds)
                        sql"""select rulepkeyid, directiveid
                              from rulesdirectivesjoin
                              where rulepkeyid in (${joinIds: joinIds.type})""".query[DB.SerializedRuleDirectives].list
                    }(Monoid.liftMonoid)
    } yield {
        val dMap = directives.groupBy(_.rulePkeyId)
        val gMap = groups.groupBy(_.rulePkeyId)
        rules.map( rule => DB.Historize.fromSerializedRule(
            rule
          , gMap.getOrElse(rule.id, Seq())
          , dMap.getOrElse(rule.id, Seq())
        ) )
    }).transact(xa).run
  }


  def updateRules(rules : Seq[Rule], closable : Seq[String]) : Unit = {
    /*
     * Just insert one rule in its own transaction.
     */
    def insertRule(now: DateTime)(r: Rule) = {
      (for {
        pk <- sql"""
                insert into rules (ruleid, serial, categoryid, name, shortdescription, longdescription, isenabled, starttime)
                values (${r.id}, ${r.serial}, ${r.categoryId}, ${r.name}, ${r.shortDescription}, ${r.longDescription}, ${r.isEnabled}, ${now})
               """.update.withUniqueGeneratedKeys[Long]("rulepkeyid")
        _  <- Update[DB.SerializedRuleDirectives]("""
                 insert into rulesdirectivesjoin (rulepkeyid, rulesdirectivesjoin)
                 values (?, ?)
               """).updateMany(r.directiveIds.map(d => DB.SerializedRuleDirectives(pk, d.value)))
        _  <- Update[DB.SerializedRuleGroups]("""
                 insert into rulesgroupsjoin (rulepkeyid, targets)
                 values (?, ?)
               """).updateMany(r.targets.map(t => DB.SerializedRuleGroups(pk, t.target)))
      } yield {
        pk
      })
    }

    val now = DateTime.now


    (rules.map(x => x.id.value).toList ++ closable).toNel.map { toClose =>
      implicit val closeParam = Param.many(toClose)
      (for {
        updated  <- sql"update rules set endtime = ${Some(DateTime.now)} where endtime is null and ruleid in (${toClose: toClose.type})"
                    .update.run.transact(xa)
        inserted <- rules.map(r => insertRule(now)(r).transact(xa)).toList.sequence
      } yield {
        ()
      }).run
    }
  }

  def getOpenedGlobalSchedule() : Option[DB.SerializedGlobalSchedule[Long]] = {
    sql"select * from globalschedule where endtime is null".query[DB.SerializedGlobalSchedule[Long]].option.transact(xa).run
  }

  def updateGlobalSchedule(interval: Int, splaytime: Int, start_hour: Int, start_minute: Int  ) : Unit = {
      (for {
        updated  <- sql"update globalschedule set endtime = ${Some(DateTime.now)} where endtime is null".update.run
        inserted <- sql"""insert into globalschedule (interval, splaytime, start_hour, start_minute, starttime)
                          values($interval, $splaytime, $start_hour, $start_minute, ${DateTime.now})""".update.run
      } yield {
        ()
      }).transact(xa).run
  }

}
