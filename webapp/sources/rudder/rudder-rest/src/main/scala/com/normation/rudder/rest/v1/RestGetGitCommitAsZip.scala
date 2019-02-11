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

import net.liftweb.http._
import net.liftweb.http.rest._
import com.normation.rudder.repository.xml.GitFindUtils
import com.normation.cfclerk.services.GitRepositoryProvider
import net.liftweb.common._
import org.joda.time.DateTime
import org.eclipse.jgit.revwalk.RevWalk
import org.joda.time.format.DateTimeFormat
import net.liftweb.util.Helpers
import org.joda.time.format.DateTimeFormatterBuilder

import com.normation.zio._
import com.normation.box._

/**
 * A rest api that allows to deploy promises.
 *
 */
class RestGetGitCommitAsZip(
   repo : GitRepositoryProvider
) extends RestHelper with Loggable {

  //date formater for reports:
  private[this] val format = new DateTimeFormatterBuilder()
                                   .append(DateTimeFormat.forPattern("YYYY-MM-dd"))
                                   .appendLiteral('T')
                                   .append(DateTimeFormat.forPattern("hhmmss")).toFormatter

  val directiveFiles = List("directives","techniques", "parameters", "ncf")

  val ruleFiles = List("rules","ruleCategories")

  val allFiles = "groups" :: ruleFiles ::: directiveFiles

  serve {
    case Get("api" :: "archives" :: "zip" :: "groups"     :: commitId :: Nil, req) =>
      getZip(commitId, List("groups"), "groups")

    case Get("api" :: "archives" :: "zip" :: "directives" :: commitId :: Nil, req) =>
      getZip(commitId, directiveFiles, "directives")

    case Get("api" :: "archives" :: "zip" :: "rules"      :: commitId :: Nil, req) =>
      getZip(commitId, ruleFiles, "rules")

    case Get("api" :: "archives" :: "zip" :: "all"        :: commitId :: Nil, req) =>
      getZip(commitId, allFiles, "all")

    /*
     * same archives methods for the authenticated part only. That is needed until
     * we have a fully authenticated (cli available) version of our REST API,
     * because else, user who whish to use API from scripts tends to make the
     * API not authenticated but restricted to localhost.
     * See http://www.rudder-project.org/redmine/issues/2990
     */
    case Get("secure" :: "utilities" :: "archiveManagement" :: "zip" :: "groups"     :: commitId :: Nil, req) =>
      getZip(commitId, List("groups"), "groups")

    case Get("secure" :: "utilities" :: "archiveManagement" :: "zip" :: "directives" :: commitId :: Nil, req) =>
      getZip(commitId, directiveFiles, "directives")

    case Get("secure" :: "utilities" :: "archiveManagement" :: "zip" :: "rules"      :: commitId :: Nil, req) =>
      getZip(commitId, ruleFiles, "rules")

    case Get("secure" :: "utilities" :: "archiveManagement" :: "zip" :: "all"        :: commitId :: Nil, req) =>
      getZip(commitId, allFiles, "all")

  }

  private[this] def getZip(commitId:String, paths:List[String], archiveType: String) = {
    val db = repo.db.runNow
    val rw = new RevWalk(db)

    (for {
      revCommit <- try {
                    val id = db.resolve(commitId)
                    Full(rw.parseCommit(id))
                  } catch {
                    case e:Exception => Failure("Error when retrieving commit revision for " + commitId, Full(e), Empty)
                  } finally {
                    rw.dispose
                  }
      treeId    <- Helpers.tryo { revCommit.getTree.getId }
      bytes     <- GitFindUtils.getZip(db, treeId, paths).toBox
    } yield {
      (bytes, format.print(new DateTime(revCommit.getCommitTime.toLong * 1000)))
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to get archive as a Zip"
        e.rootExceptionCause.foreach { ex =>
          logger.debug("Root exception was:", ex)
        }
        PlainTextResponse(e.messageChain, 500)
      case Full((bytes, date)) =>
        InMemoryResponse(
            bytes
          , ("Content-Type" -> "application/zip") ::
            ("Content-Disposition","""attachment;filename="rudder-conf-%s-%s.zip"""".format(archiveType, date)) ::
            Nil
         , Nil
         , 200
       )
    }
  }

}
