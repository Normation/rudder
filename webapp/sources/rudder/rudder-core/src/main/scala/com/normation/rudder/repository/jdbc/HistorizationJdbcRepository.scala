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
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.repository.HistorizationRepository
import doobie._
import doobie.implicits._
import cats._
import cats.data._
import cats.implicits._
import org.joda.time.DateTime
import com.normation.cfclerk.domain.Technique
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.db.Doobie._
import com.normation.zio.ZioRuntime
import zio._
import zio.interop.catz._

class HistorizationJdbcRepository(db: Doobie) extends HistorizationRepository with  Loggable {

  import db._

  def getAllOpenedNodes(): Seq[DB.SerializedNodes[Long]] = {
    transactRun(xa => sql"select id, nodeid, nodename, nodedescription, starttime, endtime from nodes where endtime is null".query[DB.SerializedNodes[Long]].to[Vector].transact(xa))
  }


  private[this] def updateQuery(idList: List[String], table: String, id: String) = {
    idList match {
      case Nil => None
      case ids =>
        val values = ids.map(x => s"('${x}')").mkString(",")
        Some(
          Fragment.const(s"update ${table} ") ++ fr"set endtime = ${Some(DateTime.now)} where endtime is null and " ++
          Fragment.const(s"${id} in (values ${values} )")
        )
    }
  }

  def updateNodes(nodes: Seq[NodeInfo], closable: Seq[String]): Unit = {
    updateQuery(nodes.map(_.id.value).toList ++ closable, "nodes", "nodeid") match {
      case None              =>  //nothing to do
      case Some(updateQuery) =>
        transactRun(xa => (for {
          updated  <- updateQuery.update.run
          inserted <- Update[DB.SerializedNodes[Unit]](
                        "insert into nodes (nodeid, nodename, nodedescription, starttime, endtime) values (?, ?, ?, ?, ?)"
                      ).updateMany(nodes.map(DB.Historize.fromNode).toList)
        } yield {
          ()
        }).transact(xa))
    }
  }

  /**
   * Fetch the serialization of groups that are still open (endTime is null), along with the
   * nodes within
   */
  def getAllOpenedGroups(): Seq[(DB.SerializedGroups[Long], Seq[DB.SerializedGroupsNodes])] = {
//    implicit object monoid extends MonoidConnectionIOList[DB.SerializedGroupsNodes]

    val action = for {
      groups     <- sql"""select id, groupid, groupname, groupdescription, nodecount, groupstatus, starttime, endtime
                          from groups where endtime is null""".query[DB.SerializedGroups[Long]].to[List]
      joinGroups <- groups.map(g => s"(${g.id})").toNel.foldMap { joinIds =>
                      (
                        Fragment.const(s"with tempgroupid (id) as (values ${joinIds.toList.mkString(",")} )") ++
                        fr"""select grouppkeyid, nodeid from groupsnodesjoin
                             inner join tempgroupid on tempgroupid.id = groupsnodesjoin.grouppkeyid
                          """
                      ).query[DB.SerializedGroupsNodes].to[Vector]
                    }(Applicative.monoid)
    } yield {
      val byIds = joinGroups.groupBy(_.groupPkeyId)
      groups.map(x => (x, byIds.getOrElse(x.id, Nil)))
    }

    transactRun(xa => action.transact(xa))
  }


  def updateGroups(groups : Seq[NodeGroup], closable : Seq[String]): Unit = {
    updateQuery(groups.map(_.id.value).toList ++ closable, "groups", "groupid") match {
      case None              =>  //nothing to do
      case Some(updateQuery) =>
        transactRun(xa => (for {
          updated  <- updateQuery.update.run
          inserted <- Update[DB.SerializedGroups[Unit]](
                        "insert into groups (groupid, groupname, groupdescription, nodecount, groupstatus, starttime, endtime) values (?, ?, ?, ?, ?, ?, ?)"
                      ).updateMany(groups.map(DB.Historize.fromNodeGroup).toList)
        } yield {
          ()
        }).transact(xa))
      }
  }

  def getAllOpenedDirectives(): Seq[DB.SerializedDirectives[Long]] = {
    transactRun(xa => sql"""select id, directiveid, directivename, directivedescription, priority, techniquename,
                  techniquehumanname, techniquedescription, techniqueversion, starttime, endtime
          from directives
          where endtime is null""".query[DB.SerializedDirectives[Long]].to[Vector].transact(xa))
  }

  def updateDirectives(
      directives : Seq[(Directive, ActiveTechnique, Technique)]
    , closable : Seq[String]
  ): Unit = {
    updateQuery(directives.map(_._1.id.value).toList ++ closable, "directives", "directiveid") match {
      case None              =>  //nothing to do
      case Some(updateQuery) =>
        transactRun(xa => (for {
          updated  <- updateQuery.update.run
          inserted <- Update[DB.SerializedDirectives[Unit]]("""
                        insert into directives (directiveid, directivename, directivedescription, priority, techniquename,
                        techniquehumanname, techniquedescription, techniqueversion, starttime, endtime) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                      """).updateMany(directives.map(DB.Historize.fromDirective).toList)
        } yield {
          ()
        }).transact(xa))
    }
  }

  def getAllOpenedRules() : Seq[Rule] = {
    def joinIdsFrag(joinIds: NonEmptyList[Long]) = {
      Fragment.const(s"rulepkeyid in ( values ${joinIds.toList.map(x => s"(${x.toString})").mkString(",")} )")
    }

    transactRun(xa => (for {
      rules      <- sql"""select rulepkeyid, ruleid, categoryid, name, shortdescription,
                                 longdescription, isenabled, starttime, endtime
                          from rules
                          where endtime is null""".query[DB.SerializedRules[Long]].to[List]
      ruleIds    =  NonEmptyList.fromList(rules.map( _.id))
      groups     <- ruleIds.foldMap { joinIds =>
                        (fr"""select rulepkeyid, targetserialisation
                              from rulesgroupjoin
                              where """ ++ joinIdsFrag(joinIds)).query[DB.SerializedRuleGroups].to[Vector]
                    }(Applicative.monoid)
      directives <- ruleIds.foldMap { joinIds =>
                        (fr"""select rulepkeyid, directiveid
                              from rulesdirectivesjoin
                              where """ ++ joinIdsFrag(joinIds)).query[DB.SerializedRuleDirectives].to[Vector]
                    }(Applicative.monoid)
    } yield {
        val dMap = directives.groupBy(_.rulePkeyId)
        val gMap = groups.groupBy(_.rulePkeyId)
        rules.map( rule => DB.Historize.fromSerializedRule(
            rule
          , gMap.getOrElse(rule.id, Seq())
          , dMap.getOrElse(rule.id, Seq())
        ) )
    }).transact(xa))
  }


  def updateRules(rules : Seq[Rule], closable : Seq[String]) : Unit = {
    /*
     * Just insert one rule in its own transaction.
     */
    def insertRule(now: DateTime)(r: Rule) = {
      (for {
        //as of 4.3, serial is always 0 before being totally deleted in future version
        pk <- sql"""
                insert into rules (ruleid, serial, categoryid, name, shortdescription, longdescription, isenabled, starttime)
                values (${r.id}, 0, ${r.categoryId}, ${r.name}, ${r.shortDescription}, ${r.longDescription}, ${r.isEnabled}, ${now})
               """.update.withUniqueGeneratedKeys[Long]("rulepkeyid")
        _  <- Update[DB.SerializedRuleDirectives]("""
                 insert into rulesdirectivesjoin (rulepkeyid, directiveid)
                 values (?, ?)
               """).updateMany(r.directiveIds.toList.map(d => DB.SerializedRuleDirectives(pk, d.value)))
        _  <- Update[DB.SerializedRuleGroups]("""
                 insert into rulesgroupjoin (rulepkeyid, targetserialisation)
                 values (?, ?)
               """).updateMany(r.targets.toList.map(t => DB.SerializedRuleGroups(pk, t.target)))
      } yield {
        pk
      })
    }

    val now = DateTime.now

    updateQuery(rules.map(_.id.value).toList ++ closable, "rules", "ruleid") match {
      case None              =>  //nothing to do
      case Some(updateQuery) =>
        ZioRuntime.unsafeRun(for {
          updated  <- transactTask(xa => updateQuery.update.run.transact(xa))
          inserted <- ZIO.collectAll(rules.map(r => transactTask(xa => insertRule(now)(r).transact(xa))))
        } yield {
          ()
        })
    }
  }

  def getOpenedGlobalSchedule() : Option[DB.SerializedGlobalSchedule[Long]] = {
    transactRun(xa => sql"select id, interval, splaytime, start_hour, start_minute, starttime, endtime from globalschedule where endtime is null".query[DB.SerializedGlobalSchedule[Long]].option.transact(xa))
  }

  def updateGlobalSchedule(interval: Int, splaytime: Int, start_hour: Int, start_minute: Int  ) : Unit = {
      transactRun(xa => (for {
        updated  <- sql"update globalschedule set endtime = ${Some(DateTime.now)} where endtime is null".update.run
        inserted <- sql"""insert into globalschedule (interval, splaytime, start_hour, start_minute, starttime)
                          values($interval, $splaytime, $start_hour, $start_minute, ${DateTime.now})""".update.run
      } yield {
        ()
      }).transact(xa))
  }

}
