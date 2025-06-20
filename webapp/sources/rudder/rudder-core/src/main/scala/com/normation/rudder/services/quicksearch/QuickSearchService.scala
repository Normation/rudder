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

package com.normation.rudder.services.quicksearch

import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.ncf.EditorTechniqueReader
import com.normation.rudder.repository.RoDirectiveRepository
import zio.ZIO

/**
 * This class allow to return a list of Rudder object given a string.
 * The string is search in some attribute
 * (node name, hostname, uuid, directive attribute, etc).
 * The service always return both the matching attribute and the
 * object type and UUID
 */
class FullQuickSearchService(implicit
    val ldapConnection:  LDAPConnectionProvider[RoLDAPConnection],
    val nodeDit:         NodeDit,
    val inventoryDit:    InventoryDit,
    val rudderDit:       RudderDit,
    val directiveRepo:   RoDirectiveRepository,
    val nodeInfos:       NodeFactRepository,
    val techniqueReader: EditorTechniqueReader
) {

  import QuickSearchService.*
  val logger = FullQuickSearchServiceLoggerPure

  /**
   * Search in nodes, groups, directives, rules, parameters for the object
   * containing token.
   * The results are raw: they are not sorted, and may be not unique for
   * a given id (i.e, we can have to answer for the node id "root").
   * Limit parameter when present is used to take elements in raw results :
   * the same limit of elements for each type of backend result, contrary to a global limit
   * (applying the limit takes the first elements of backend results).
   *
   */
  def search(token: String, limit: Option[Int])(implicit qc: QueryContext): IOResult[Set[QuickSearchResult]] = {
    for {
      query   <- token.parse()
      _       <- logger.debug(
                   s"User query for '${token}', parsed as user query: '${query.userToken}' on objects: " +
                   s"'${query.objectClass.mkString(", ")}' and attributes '${query.attributes.mkString(", ")}'"
                 )
      results <- ZIO.foreach(QSBackend.values)(b => {
                   b.search(query)
                     .tap(results => logger.debug(s"  - [${b}] found ${results.size} results"))
                     .map(limit match {
                       case Some(value) => _.take(value)
                       case None        => identity
                     })
                     .chainError(s"Error with quicksearch bachend ${b}")
                 })
    } yield {
      results.flatten.toSet
    }
  }

}

object FullQuickSearchServiceLoggerPure extends NamedZioLogger {
  override def loggerName = "com.normation.rudder.services.quicksearch.FullQuickSearchService"
}

object QuickSearchService {

  implicit class QSParser(val query: String) extends AnyVal {
    def parse(): IOResult[Query] = QSRegexQueryParser.parse(query).toIO
  }

  implicit class QSBackendImpl(b: QSBackend)(implicit
      directiveRepo:   RoDirectiveRepository,
      ldap:            LDAPConnectionProvider[RoLDAPConnection],
      rudderDit:       RudderDit,
      nodeFactRepo:    NodeFactRepository,
      techniqueReader: EditorTechniqueReader
  ) {

    import com.normation.rudder.services.quicksearch.QSBackend.*

    def search(query: Query)(implicit qc: QueryContext): IOResult[Seq[QuickSearchResult]] = b match {
      case LdapBackend      => QSLdapBackend.search(query)
      case DirectiveBackend => QSDirectiveBackend.search(query)
      case TechniqueBackend => QSTechniqueBackend.search(query)
      case NodeFactBackend  => QSNodeFactBackend.search(query)
    }

  }

}
