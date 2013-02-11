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

/**
 * A rest api that allows to deploy promises.
 *
 */
class RestGetGitCommitAsZip(
   repo : GitRepositoryProvider
) extends RestHelper {

  serve {
    case Get("api" :: "archives" :: "zip" :: "groups"     :: commitId :: Nil, req) =>
      getZip(commitId, List("groups"))

    case Get("api" :: "archives" :: "zip" :: "directives" :: commitId :: Nil, req) =>
      getZip(commitId, List("directives"))

    case Get("api" :: "archives" :: "zip" :: "rules"      :: commitId :: Nil, req) =>
      getZip(commitId, List("rules"))

    case Get("api" :: "archives" :: "zip" :: "all"        :: commitId :: Nil, req) =>
      getZip(commitId, List("groups", "directives", "rules"))


    /*
     * same archives methods for the authenticated part only. That is needed until
     * we have a fully authenticated (cli available) version of our REST API,
     * because else, user who whish to use API from scripts tends to make the
     * API not authenticated but restricted to localhost.
     * See http://www.rudder-project.org/redmine/issues/2990
     */
    case Get("secure" :: "administration" :: "archiveManagement" :: "zip" :: "groups"     :: commitId :: Nil, req) =>
      getZip(commitId, List("groups"))

    case Get("secure" :: "administration" :: "archiveManagement" :: "zip" :: "directives" :: commitId :: Nil, req) =>
      getZip(commitId, List("directives"))

    case Get("secure" :: "administration" :: "archiveManagement" :: "zip" :: "rules"      :: commitId :: Nil, req) =>
      getZip(commitId, List("rules"))

    case Get("secure" :: "administration" :: "archiveManagement" :: "zip" :: "all"        :: commitId :: Nil, req) =>
      getZip(commitId, List("groups", "directives", "rules"))


  }

  private[this] def getZip(commitId:String, paths:List[String]) = {
      (for {
        treeId <- GitFindUtils.findRevTreeFromRevString(repo.db, commitId)
        bytes  <- GitFindUtils.getZip(repo.db, treeId, paths)
      } yield {
        bytes
      }) match {
        case eb:EmptyBox => PlainTextResponse("Error when trying to get archive as a Zip", 500)
        case Full(bytes) =>
          InMemoryResponse(
              bytes
            , ("Content-Type" -> "application/zip") ::
              ("Content-Disposition","""attachment;filename="rudder-configuration-archive-id_%s.zip"""".format(commitId)) ::
              Nil
           , Nil
           , 200
         )
      }
  }

}