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
import org.eclipse.jgit.revwalk.RevTree
import com.normation.rudder.repository.xml.GitFindUtils
import com.normation.cfclerk.services.GitRepositoryProvider
import net.liftweb.common._
import org.eclipse.jgit.lib.ObjectId
import org.joda.time.DateTime
import org.eclipse.jgit.revwalk.RevWalk
import org.joda.time.format.DateTimeFormat
import org.eclipse.jgit.treewalk.TreeWalk
import net.liftweb.util.Helpers
import org.joda.time.format.DateTimeFormatterBuilder

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

  serve {
    case Get("api" :: "archives" :: "zip" :: "groups"     :: commitId :: Nil, req) =>
      getZip(commitId, List("groups"), "groups")

    case Get("api" :: "archives" :: "zip" :: "directives" :: commitId :: Nil, req) =>
      getZip(commitId, List("directives"), "directives")

    case Get("api" :: "archives" :: "zip" :: "rules"      :: commitId :: Nil, req) =>
      getZip(commitId, List("rules"), "rules")

    case Get("api" :: "archives" :: "zip" :: "all"        :: commitId :: Nil, req) =>
      getZip(commitId, List("groups", "directives", "rules"), "all")


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
      getZip(commitId, List("directives"), "directives")

    case Get("secure" :: "utilities" :: "archiveManagement" :: "zip" :: "rules"      :: commitId :: Nil, req) =>
      getZip(commitId, List("rules"), "rules")

    case Get("secure" :: "utilities" :: "archiveManagement" :: "zip" :: "all"        :: commitId :: Nil, req) =>
      getZip(commitId, List("groups", "directives", "rules"), "all")


  }

  private[this] def getZip(commitId:String, paths:List[String], archiveType: String) = {
    val rw = new RevWalk(repo.db)

    (for {
      revCommit <- try {
                    val id = repo.db.resolve(commitId)
                    Full(rw.parseCommit(id))
                  } catch {
                    case e:Exception => Failure("Error when retrieving commit revision for " + commitId, Full(e), Empty)
                  } finally {
                    rw.dispose
                  }
      treeId    <- Helpers.tryo { revCommit.getTree.getId }
      bytes     <- GitFindUtils.getZip(repo.db, treeId, paths)
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
