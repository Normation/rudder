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

package com.normation.rudder.rest.v1

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.UserService
import com.normation.rudder.repository._
import com.normation.rudder.repository.xml._
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.rest._
import net.liftweb.json.JsonDSL._
import net.liftweb.json._
import net.liftweb.util.Helpers.tryo
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime
import com.normation.box._
import com.normation.errors._
import com.normation.rudder.rest.RestUtils
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * A rest api that allows to deploy promises.
 *
 */
class RestArchiving(
    itemArchiveManager: ItemArchiveManager
  , personIdentService: PersonIdentService
  , uuidGen           : StringUuidGenerator
) ( implicit userService : UserService )
extends RestHelper {

  //  List archives: /api/archives/list
  serve {
    case Get("api" :: "archives" :: "list" :: "groups" :: Nil, _) =>
      listTags(itemArchiveManager.getGroupLibraryTags _, "groups")

    case Get("api" :: "archives" :: "list" :: "directives" :: Nil, _) =>
      listTags(itemArchiveManager.getTechniqueLibraryTags _, "technique library")

    case Get("api" :: "archives" :: "list" :: "rules" :: Nil, _) =>
      listTags(itemArchiveManager.getRulesTags _, "rules")

    case Get("api" :: "archives" :: "list" :: "full" :: Nil, _) =>
      listTags(itemArchiveManager.getFullArchiveTags _, "full archive")
  }

  // restore last archive /api/archives/restore/{groups,..}/latestArchive
  serve {
    case Get("api" :: "archives" :: "restore" :: "groups" :: "latestArchive" :: Nil, req) =>
      restoreLatestArchive(req, itemArchiveManager.getGroupLibraryTags _, itemArchiveManager.importGroupLibrary, "groups")

    case Get("api" :: "archives" :: "restore" :: "directives" :: "latestArchive" :: Nil, req) =>
      restoreLatestArchive(req, itemArchiveManager.getTechniqueLibraryTags _, itemArchiveManager.importTechniqueLibrary, "technique library")

    case Get("api" :: "archives" :: "restore" :: "rules" :: "latestArchive" :: Nil, req) =>
      restoreLatestArchive(req, itemArchiveManager.getRulesTags _, itemArchiveManager.importRules, "rules")

    case Get("api" :: "archives" :: "restore" :: "full" :: "latestArchive" :: Nil, req) =>
      restoreLatestArchive(req, itemArchiveManager.getFullArchiveTags _, itemArchiveManager.importAll, "full archive")
  }

  // restore from Git HEAD /api/archives/restore/{groups,..}/latestCommit
  serve {
    case Get("api" :: "archives" :: "restore" :: "groups" :: "latestCommit" :: Nil, req) =>
      restoreLatestCommit(req, itemArchiveManager.importHeadGroupLibrary, "groups")

    case Get("api" :: "archives" :: "restore" :: "directives" :: "latestCommit" :: Nil, req) =>
      restoreLatestCommit(req, itemArchiveManager.importHeadTechniqueLibrary, "technique library")

    case Get("api" :: "archives" :: "restore" :: "rules" :: "latestCommit" :: Nil, req) =>
      restoreLatestCommit(req, itemArchiveManager.importHeadRules, "rules")

    case Get("api" :: "archives" :: "restore" :: "full" :: "latestCommit" :: Nil, req) =>
      restoreLatestCommit(req, itemArchiveManager.importHeadAll, "full archive")
  }

  // archive /api/archives/archive/{groups, ...}
  serve {
    case Get("api" :: "archives" :: "archive" :: "groups" :: Nil, req) =>
      archive(req, itemArchiveManager.exportGroupLibrary _, "groups")

    case Get("api" :: "archives" :: "archive" :: "directives" :: Nil, req) =>
      archive(req, ((a,b,c,d,e) => itemArchiveManager.exportTechniqueLibrary(a,b,c,d,e).map( _._1)), "technique library")

    case Get("api" :: "archives" :: "archive" :: "rules" :: Nil, req) =>
      archive(req, itemArchiveManager.exportRules _, "rules")

    case Get("api" :: "archives" :: "archive" :: "full" :: Nil, req) =>
      archive(req,  ((a,b,c,d,e) => itemArchiveManager.exportAll(a,b,c,d,e).map( _._1)), "full archive")
  }

  //restore a given archive
  // restore last archive /api/archives/restore/{groups,..}/latest
  serve {
    case Get("api" :: "archives" :: "restore" :: "groups" :: "datetime" :: datetime :: Nil, req) =>
      restoreByDatetime(req, itemArchiveManager.getGroupLibraryTags _, itemArchiveManager.importGroupLibrary, datetime, "groups")

    case Get("api" :: "archives" :: "restore" :: "directives" :: "datetime" :: datetime :: Nil, req) =>
      restoreByDatetime(req, itemArchiveManager.getTechniqueLibraryTags _, itemArchiveManager.importTechniqueLibrary, datetime, "technique library")

    case Get("api" :: "archives" :: "restore" :: "rules" :: "datetime" :: datetime :: Nil, req) =>
      restoreByDatetime(req, itemArchiveManager.getRulesTags _, itemArchiveManager.importRules, datetime, "rules")

    case Get("api" :: "archives" :: "restore" :: "full" :: "datetime" :: datetime :: Nil, req) =>
      restoreByDatetime(req, itemArchiveManager.getFullArchiveTags _, itemArchiveManager.importAll, datetime, "full archive")
  }

  ////////////////////////////////////////////////////////////
  ////////////////// implementation details //////////////////
  ////////////////////////////////////////////////////////////

  private[this] def listTags(list:() => IOResult[Map[DateTime, GitArchiveId]], archiveType:String) = {
      list().toBox match {
        case eb : EmptyBox =>
          val e = eb ?~! "Error when trying to list available archives for %s".format(archiveType)
          PlainTextResponse(e.messageChain, 503)
        case Full(map) =>
          JsonResponse(formatList(archiveType, map))
      }
  }

  private[this] def newModId = ModificationId(uuidGen.newUuid)

  /**
   * that produce a JSON file:
   * (the most recent is the first in the array)
   * { "archiveType" : [ { "id" : "datetimeID", "date": "human readable date" , "commiter": "name", "gitPath": "path" }, ... ]
   */
  private[this] def formatList(archiveType:String, availableArchives: Map[DateTime, GitArchiveId]) : JValue = {
    case class JsonArchive(id:String, date:String, commiter:String, gitPath:String)
    val ordered = availableArchives.toList.sortWith {
                    case ( (d1,_), (d2,_) ) => d1.isAfter(d2)
                  }.map {
                    case (date,tag) =>
                        val id = date.toString(GitTagDateTimeFormatter)
                        val datetime = "%s at %s".format(date.toString("YYYY-MM-dd"), date.toString("HH:mm:ss"))
                        //json
                        ("id" -> id) ~ ("date" -> datetime) ~ ("commiter" -> tag.commiter.getName) ~ ("gitCommit" -> tag.commit.value)
                  }

    JObject(JField(archiveType,  ordered))
  }

  private[this] def restoreLatestArchive(req:Req, list:() => IOResult[Map[DateTime, GitArchiveId]], restore:(GitCommitId,PersonIdent,ModificationId,EventActor,Option[String],Boolean) => IOResult[GitCommitId], archiveType:String) = {
    (for {
      archives   <- list().toBox
      commiter   <- personIdentService.getPersonIdentOrDefault(RestUtils.getActor(req).name).toBox
      (date,tag) <- Box(archives.toList.sortWith { case ( (d1,_), (d2,_) ) => d1.isAfter(d2) }.headOption) ?~! "No archive is available"
      restored   <- restore(tag.commit,commiter,newModId,RestUtils.getActor(req),Some("Restore latest archive required from REST API"),false).toBox
    } yield {
      restored
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to restore the latest archive for %s.".format(archiveType)
        PlainTextResponse(e.messageChain, 503)
      case Full(x) =>
        PlainTextResponse("OK")
    }
  }

  private[this] def restoreLatestCommit(req:Req, restore: (PersonIdent,ModificationId,EventActor,Option[String],Boolean) => IOResult[GitCommitId], archiveType:String) = {
    (for {
      commiter   <- personIdentService.getPersonIdentOrDefault(RestUtils.getActor(req).name).toBox
      restored   <- restore(commiter,newModId,RestUtils.getActor(req),Some("Restore archive from latest commit on HEAD required from REST API"), false).toBox
    } yield {
      restored
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to restore the latest archive for %s.".format(archiveType)
        PlainTextResponse(e.messageChain, 503)
      case Full(x) =>
        PlainTextResponse("OK")
    }
  }

  private[this] def restoreByDatetime(req:Req, list:() => IOResult[Map[DateTime, GitArchiveId]], restore:(GitCommitId,PersonIdent,ModificationId,EventActor,Option[String],Boolean) => IOResult[GitCommitId], datetime:String, archiveType:String) = {
    (for {
      valideDate <- tryo { GitTagDateTimeFormatter.parseDateTime(datetime) } ?~! "The given archive id is not a valid archive tag: %s".format(datetime)
      archives   <- list().toBox
      commiter   <- personIdentService.getPersonIdentOrDefault(RestUtils.getActor(req).name).toBox
      tag        <- Box(archives.get(valideDate)) ?~! "The archive with tag '%s' is not available. Available archives: %s".format(datetime,archives.keySet.map( _.toString(GitTagDateTimeFormatter)).mkString(", "))
      restored   <- restore(tag.commit,commiter,newModId,RestUtils.getActor(req),Some("Restore archive for date time %s requested from REST API".format(datetime)),false).toBox
    } yield {
      restored
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to restore archive '%s' for %s.".format(datetime, archiveType)
        PlainTextResponse(e.messageChain, 503)
      case Full(x) =>
        PlainTextResponse("OK")
    }
  }

  private[this] def archive(req:Req, archive:(PersonIdent,ModificationId,EventActor,Option[String],Boolean) => IOResult[GitArchiveId], archiveType:String) = {
    (for {
      commiter  <- personIdentService.getPersonIdentOrDefault(RestUtils.getActor(req).name)
      archiveId <- archive(commiter,newModId,RestUtils.getActor(req),Some("Create new archive requested from REST API"),false)
    } yield {
      archiveId
    }).toBox match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to archive %s.".format(archiveType)
        PlainTextResponse(e.messageChain, 503)
      case Full(x) =>
        PlainTextResponse("OK")
    }
  }
}
