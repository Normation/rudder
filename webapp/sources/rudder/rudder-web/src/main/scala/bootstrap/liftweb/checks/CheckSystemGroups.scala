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

package bootstrap.liftweb
package checks

import net.liftweb.common._
import org.slf4j.LoggerFactory
import net.liftweb.common.Logger
import net.liftweb.common.Failure
import com.normation.utils.Control.sequence
import com.normation.eventlog.ModificationId
import com.normation.ldap.sdk.{BuildFilter, LDAPConnectionProvider, LDAPEntry, RoLDAPConnection}
import BuildFilter._
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.domain.{RudderDit, RudderLDAPConstants}
import RudderLDAPConstants._
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.queries._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.repository.ldap.ScalaReadWriteLock
import com.normation.box._
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * The goal is to check that system groups (name are "hasPolicyServer-")
 * are dynamic and they have a correct query
 * This will allow to update them automatically via the update group batch
 */
class CheckSystemGroups(
    rudderDit     : RudderDit
  , ldap          : LDAPConnectionProvider[RoLDAPConnection]
  , mapper        : LDAPEntityMapper
  , groupLibMutex : ScalaReadWriteLock
  , rwRepos       : WoNodeGroupRepository
  , uuidGen       : StringUuidGenerator
) extends BootstrapChecks {

  override val description = "Check that system groups 'hasPolicyServer-*' are dynamics and correct them"

  private[this] object logger extends Logger {
    override protected def _logger = LoggerFactory.getLogger("migration")
    val defaultErrorLogger : Failure => Unit = { f =>
      logger.error(f.messageChain)
      f.rootExceptionCause.foreach { ex =>
        logger.error("Root exception was:", ex)
      }
    }
  }

  private[this] val filter = {
    AND(
        IS(OC_RUDDER_NODE_GROUP)
      , MATCH(A_NODE_GROUP_UUID, "hasPolicyServer-")
      , OR (
          EQ(A_IS_DYNAMIC, "FALSE")
        , NOT(HAS(A_QUERY_NODE_GROUP))
      )
    )
  }

  def extractPolicyServerId (groupId : NodeGroupId) = {
    val regexp ="hasPolicyServer-([\\w-]+)".r
    regexp.findFirstMatchIn(groupId.value) match {
      case Some(matching) => matching.group(1).succeed
      case None => Unconsistancy(s"could not extract policy server ID from group '${groupId.value}'").fail
    }
  }

  def extractDataFromEntry(entry: LDAPEntry) : IOResult[(NodeGroup,String)]= {
    for {
      group <-  mapper.entry2NodeGroup(entry).toIO.chainError(s"Error when mapping server group entry to its entity. Entry: ${entry}")
      policyServerId <- extractPolicyServerId(group.id)
    } yield {
      (group,policyServerId)
    }
  }

  override def checks() : Unit = {

    ( groupLibMutex.readLock {
      // Get all groups matching the filter
      for {
      con <- ldap
      entries <- con.searchSub(rudderDit.GROUP.dn,  filter)
      groups <- ZIO.foreach(entries)(extractDataFromEntry)
    } yield {
      groups
    } } ).toBox match {
      case Full(groups) => {
        for {
          (group,policyServerId) <- groups
        } yield {
          val message = s"Migrate system group '${group.name}' to become a dynamic group"
          val modId = ModificationId(uuidGen.newUuid)
          // hasPolicyServer Query : nodes using that policyServerId
          val criterion = Criterion("policyServerId",StringComparator)
          val criteria = CriterionLine(
              ObjectCriterion (
                  "node"
                , Seq(criterion)
              )
            , criterion
            , Equals
            , policyServerId
          )
          val query = Query(NodeAndPolicyServerReturnType,And,criteria :: Nil)

          val updatedGroup = group.copy(isDynamic = true, query = Some(query))
          rwRepos.updateSystemGroup(updatedGroup, modId, RudderEventActor, Some(message)).toBox
          BootraspLogger.logEffect.info(message)

        }
      }
      case eb =>
        val message = "Could not migrate system groups"
        val fail = eb ?~ message
        BootraspLogger.logEffect.error(fail)
    }
  }
}

