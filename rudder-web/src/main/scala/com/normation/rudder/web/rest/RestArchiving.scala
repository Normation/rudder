/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.rest

import net.liftweb.http._
import net.liftweb.http.rest._
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.ManualStartDeployment
import com.normation.rudder.repository.ItemArchiveManager
import net.liftweb.common._
import com.normation.rudder.repository.xml.GitTagDateTimeFormatter
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import org.eclipse.jgit.revwalk.RevTag
import org.joda.time.DateTime
import com.normation.rudder.repository.ArchiveId
import org.eclipse.jgit.lib.PersonIdent
import com.normation.rudder.services.user.PersonIdentService
import net.liftweb.util.Helpers.tryo
  
/**
 * A rest api that allows to deploy promises.
 * 
 */
class RestArchiving(
    itemArchiveManager: ItemArchiveManager
  , personIdentService: PersonIdentService
) extends RestHelper {
 
  //  List archives: /api/archives/list
  serve {
    case Get("api" :: "archives" :: "list" :: "groups" :: Nil, _) => 
      listTags(itemArchiveManager.getGroupLibraryTags _, "groups")

    case Get("api" :: "archives" :: "list" :: "policyLibrary" :: Nil, _) => 
      listTags(itemArchiveManager.getPolicyLibraryTags _, "policy library")

    case Get("api" :: "archives" :: "list" :: "configurationRules" :: Nil, _) => 
      listTags(itemArchiveManager.getConfigurationRulesTags _, "configuration rules")

    case Get("api" :: "archives" :: "list" :: "full" :: Nil, _) => 
      listTags(itemArchiveManager.getFullArchiveTags _, "full archive")
  }
  
  // restore last archive /api/archives/restore/{groups,..}/latestArchive
  serve {
    case Get("api" :: "archives" :: "restore" :: "groups" :: "latestArchive" :: Nil, _) => 
      restoreLatestArchive(itemArchiveManager.getGroupLibraryTags _, itemArchiveManager.importGroupLibrary, "groups")

    case Get("api" :: "archives" :: "restore" :: "policyLibrary" :: "latestArchive" :: Nil, _) =>
      restoreLatestArchive(itemArchiveManager.getPolicyLibraryTags _, itemArchiveManager.importPolicyLibrary, "policy library")

    case Get("api" :: "archives" :: "restore" :: "configurationRules" :: "latestArchive" :: Nil, _) => 
      restoreLatestArchive(itemArchiveManager.getConfigurationRulesTags _, itemArchiveManager.importConfigurationRules, "configuration rules")

    case Get("api" :: "archives" :: "restore" :: "full" :: "latestArchive" :: Nil, _) => 
      restoreLatestArchive(itemArchiveManager.getFullArchiveTags _, itemArchiveManager.importAll, "full archive")
  }

  // restore from Git HEAD /api/archives/restore/{groups,..}/latestCommit
  serve {
    case Get("api" :: "archives" :: "restore" :: "groups" :: "latestCommit" :: Nil, _) => 
      restoreLatestCommit(itemArchiveManager.importHeadGroupLibrary, "groups")

    case Get("api" :: "archives" :: "restore" :: "policyLibrary" :: "latestCommit" :: Nil, _) =>
      restoreLatestCommit(itemArchiveManager.importHeadPolicyLibrary, "policy library")

    case Get("api" :: "archives" :: "restore" :: "configurationRules" :: "latestCommit" :: Nil, _) => 
      restoreLatestCommit(itemArchiveManager.importHeadConfigurationRules, "configuration rules")

    case Get("api" :: "archives" :: "restore" :: "full" :: "latestCommit" :: Nil, _) => 
      restoreLatestCommit(itemArchiveManager.importHeadAll, "full archive")
  }
  
  // archive /api/archives/archive/{groups, ...}
  serve {
    case Get("api" :: "archives" :: "archive" :: "groups" :: Nil, req) => 
      archive(req, itemArchiveManager.exportGroupLibrary _, "groups")

    case Get("api" :: "archives" :: "archive" :: "policyLibrary" :: Nil, req) =>
      archive(req, itemArchiveManager.exportPolicyLibrary _, "policy library")

    case Get("api" :: "archives" :: "archive" :: "configurationRules" :: Nil, req) => 
      archive(req, itemArchiveManager.exportConfigurationRules _, "configuration rules")

    case Get("api" :: "archives" :: "archive" :: "full" :: Nil, req) => 
      archive(req, itemArchiveManager.exportAll _, "full archive")
  }
  
  //restore a given archive
  // restore last archive /api/archives/restore/{groups,..}/latest
  serve {
    case Get("api" :: "archives" :: "restore" :: "groups" :: "datetime" :: datetime :: Nil, _) => 
      restoreByDatetime(itemArchiveManager.getGroupLibraryTags _, itemArchiveManager.importGroupLibrary, datetime, "groups")

    case Get("api" :: "archives" :: "restore" :: "policyLibrary" :: "datetime" :: datetime :: Nil, _) =>
      restoreByDatetime(itemArchiveManager.getPolicyLibraryTags _, itemArchiveManager.importPolicyLibrary, datetime, "policy library")

    case Get("api" :: "archives" :: "restore" :: "configurationRules" :: "datetime" :: datetime :: Nil, _) => 
      restoreByDatetime(itemArchiveManager.getConfigurationRulesTags _, itemArchiveManager.importConfigurationRules, datetime, "configuration rules")

    case Get("api" :: "archives" :: "restore" :: "full" :: "datetime" :: datetime :: Nil, _) => 
      restoreByDatetime(itemArchiveManager.getFullArchiveTags _, itemArchiveManager.importAll, datetime, "full archive")
  }
  

  
  
  //////////////////////////////////////////////////////////// 
  ////////////////// implementation details //////////////////
  //////////////////////////////////////////////////////////// 
  
  
  private[this] def listTags(list:() => Box[Map[DateTime,RevTag]], archiveType:String) = {
      list() match {
        case eb : EmptyBox => 
          val e = eb ?~! "Error when trying to list available archives for %s".format(archiveType)
          PlainTextResponse(e.messageChain, 503)
        case Full(map) => 
          JsonResponse(formatList("groupArchives", map))
      }
  }

  /**
   * that produce a JSON file:
   * (the most recent is the first in the array)
   * { "archiveType" : [ { "id" : "datetimeID", "date": "human readable date" , "commiter": "name", "gitPath": "path" }, ... ]
   */
  private[this] def formatList(archiveType:String, availableArvhives: Map[DateTime, RevTag]) : JValue = {
    case class JsonArchive(id:String, date:String, commiter:String, gitPath:String)
    val ordered = availableArvhives.toList.sortWith { 
                    case ( (d1,_), (d2,_) ) => d1.isAfter(d2) 
                  }.map {
                    case (date,tag) => 
                        val id = date.toString(GitTagDateTimeFormatter)
                        val datetime = "%s at %s".format(date.toString("YYYY-MM-DD"), date.toString("HH:mm:ss"))
                        val commiter = tag.getTaggerIdent.getName
                        val gitPath = tag.getTagName
                        //json
                        ("id" -> id) ~ ("date" -> datetime) ~ ("commiter" -> commiter) ~ ("gitPath" -> gitPath)
                  }
                  
    JField(archiveType,  ordered)
  }

  
  private[this] def restoreLatestArchive(list:() => Box[Map[DateTime,RevTag]], restore:(RevTag,Boolean) => Box[Unit], archiveType:String) = {
    (for {
      archives   <- list()
      (date,tag) <- Box(archives.toList.sortWith { case ( (d1,_), (d2,_) ) => d1.isAfter(d2) }.headOption) ?~! "No archive is available"
      restored   <- restore(tag,false)
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
  
  private[this] def restoreLatestCommit(restore: Boolean => Box[Unit], archiveType:String) = {
    (for {
      restored   <- restore(false)
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
  
  private[this] def restoreByDatetime(list:() => Box[Map[DateTime,RevTag]], restore:(RevTag,Boolean) => Box[Unit], datetime:String, archiveType:String) = {
    (for {
      valideDate <- tryo { GitTagDateTimeFormatter.parseDateTime(datetime) } ?~! "The given archive id is not a valid archive tag: %s".format(datetime)
      archives   <- list()
      tag        <- Box(archives.get(valideDate)) ?~! "The archive with tag '%s' is not available. Available archives: %s".format(datetime,archives.keySet.map( _.toString(GitTagDateTimeFormatter)).mkString(", "))
      restored   <- restore(tag,false)
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

  private[this] def archive(req:Req, archive:(PersonIdent,Boolean) => Box[ArchiveId], archiveType:String) = {
    (for {
      commiter  <- personIdentService.getPersonIdentOrDefault(RestUtils.getActor(req).name)
      archiveId <- archive(commiter,false)
    } yield {
      archiveId
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to archive %s.".format(archiveType)
        PlainTextResponse(e.messageChain, 503)
      case Full(x) =>
        PlainTextResponse("OK")
    }
  }

}