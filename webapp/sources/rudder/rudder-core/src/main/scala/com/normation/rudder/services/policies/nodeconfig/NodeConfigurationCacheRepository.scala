/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.services.policies.nodeconfig

import cats.implicits._
import com.normation.inventory.domain.NodeId
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.A_NODE_CONFIG
import com.normation.rudder.domain.RudderLDAPConstants.OC_NODES_CONFIG
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import com.normation.cfclerk.domain.Variable
import com.normation.ldap.sdk.LdapResult._
import com.normation.rudder.services.policies.PolicyId
import com.normation.rudder.services.policies.Policy
import com.normation.rudder.services.policies.NodeConfiguration


case class PolicyHash(
    draftId   : PolicyId
  , cacheValue: Int
)

/**
 * NodeConfigurationHash keep a track of anything that would mean
 * the node policies have changed and should be rewritten.
 * We are at the node granularity check, because today, we don't
 * know how to rewrite only some directives.
 *
 * Keep in mind that anything that is changing the related resources
 * of policies that are written for the node during policy generation
 * must be taken into account in the hash.
 * Typically, the technique non-template resources, like success CFEngine
 * file or other configuration files, must be looked for change
 * (for them, this is done with the technique "acceptation" (i.e commit)
 * date.
 *
 */
case class NodeConfigurationHash(
    id             : NodeId
  , writtenDate    : DateTime
  , nodeInfoHash   : Int
  , parameterHash  : Int
  , nodeContextHash: Int
  , policyHash     : Set[PolicyHash]
) {
  // We need a method to correctly compare a NodeConfigurationHash that was serialized
  // from a NodeConfigurationHash created from a NodeConfiguration (so without writtenDate)
  def equalWithoutWrittenDate(other: NodeConfigurationHash) : Boolean = {
    id              == other.id &&
    nodeInfoHash    == other.nodeInfoHash &&
    parameterHash   == other.parameterHash &&
    nodeContextHash == other.nodeContextHash &&
    policyHash      == other.policyHash
  }
}

object NodeConfigurationHash {

  /**
   * Build the hash from a node configuration.
   *
   */
  def apply(nodeConfig: NodeConfiguration, writtenDate: DateTime): NodeConfigurationHash = {


    /*
     * A parameter update must lead to a regeneration of the node,
     * because it could be used in pur CFEngine (when used in
     * directive, we already tracked it with directives values).
     *
     * So a modification in ANY parameter lead to a regeneration
     * of ALL NODES (everything, yes).
     *
     * For parameters, do not consider:
     * - overridable
     * - description
     * But as ParameterForConfiguration only has relevant information,
     * we can simply take the hashcode.
     * Also, don't depend of the datastructure used, nor
     * of the order of the variables (so is set is good ?)
     */
    val parameterHash: Int = {
      nodeConfig.parameters.hashCode
    }


    /*
     * Take into account anything that has influence on policies but
     * without being only a directive parameter (that latter case will
     * be handle directly at the directive level) or a system variable
     * (which is already in nodeContext)
     *
     * - all ${rudder.node} params (because can be used in success CFEngine)
     * - node properties (because can be used in success CFEngine)
     * - isPolicyServer (for nodes becoming relay)
     * - serverRoles (because not the same set of directives - but
     *   perhaps it is already handle in the directives)
     * - agentsName (because directly used in templates for if conditions)
     * - any node modes config option (policy mode, compliance, agent run -
     *   being node specific or global - because directly used by generation
     *   process to modify things all around)
     *
     * - publicKey / keyStatus? (I don't see why it should be ?)
     */
    val nodeInfoHashValue = {
      val i = nodeConfig.nodeInfo
      List[Int](
        i.name.hashCode
      , i.hostname.hashCode
      , i.localAdministratorAccountName.hashCode
      , i.policyServerId.hashCode
      , i.properties.hashCode
      , i.isPolicyServer.hashCode
      , i.serverRoles.hashCode
      , i.agentsName.hashCode
      , nodeConfig.modesConfig.hashCode
      ).hashCode
    }

    /*
     * For policy draft, we want to check
     * - technique name, version, acceptation date
     *   (for acceptation date, we don't need to keep the date:
     *   either we don't have the technique in hash, and so we don't care of the date,
     *   or we have it, so we used the date for the previous cache, and so if the date
     *   is not the same it changed since the cache generation and we must regenerate)
     * - variables
     * - serial
     * - priority (may influence order of writting sequence of techniques)
     * - rule/directive order (may influense order in bundle sequence)
     * - policyMode (because influence call to "setDryMode")
     *
     * Ncf techniques update are taken into account thanks to the acceptation date.
     * System variables are tracked throught the node context afterward.
     */
    val policyHashValue = {
      nodeConfig.policies.map { case r:Policy =>
        //don't take into account "overrides" in cache: having more or less
        //ignored things must not impact the cache computation
        PolicyHash(
            r.id
          , (
              r.technique.id.hashCode
            + r.techniqueUpdateTime.hashCode
            + r.priority
            + r.ruleOrder.hashCode + r.directiveOrder.hashCode
            + r.policyMode.hashCode()
            + variablesToHash(r.expandedVars.values)
            )
        )
      }.toSet
    }

    /*
     * System variables are variables.
     *
     * Not that with that, we are taking into account
     * node config modes several time, since any mode has
     * a matching system variable.
     */
    val nodeContextHash: Int = {
      variablesToHash(nodeConfig.nodeContext.values)
    }

    new NodeConfigurationHash(
        id = nodeConfig.nodeInfo.id
      , writtenDate = writtenDate
      , nodeInfoHash = nodeInfoHashValue
      , parameterHash = parameterHash
      , nodeContextHash = nodeContextHash
      , policyHash = policyHashValue
    )
  }

  /*
   * Compute the hash cash value for a set of variables:
   * - the order does not matter
   * - duplicate (same name) variable make no sense, so we remove them
   * - only the ORDERED values matters
   *
   * Also, variable with no values are equivalent to no variable, so we
   * remove them.
   */
  private[this] def variablesToHash(variables: Iterable[Variable]): Int = {
    val z = variables.map( x => (x.spec.name, x.values) ).filterNot( _._2.isEmpty ).toSet
    z.hashCode
  }


}


/**
 * A class that keep minimum information
 * to track changement in node configuration.
 */
trait NodeConfigurationHashRepository {

  /**
   * Delete node config by its id
   */
  def deleteNodeConfigurations(nodeIds:Set[NodeId]) : Box[Set[NodeId]]

  /**
   * delete all node configuration
   */
  def deleteAllNodeConfigurations() : Box[Unit]

  /**
   * Inverse of delete: delete all node configuration not
   * given in the argument.
   */
  def onlyKeepNodeConfiguration(nodeIds:Set[NodeId]) : Box[Set[NodeId]]

  /**
   * Return all known NodeConfigurationHash
   */
  def getAll() : Box[Map[NodeId, NodeConfigurationHash]]

  /**
   * Update or add NodeConfigurationHash from parameters.
   * No existing NodeConfigurationHash is deleted.
   * Return newly cache node configuration.
   */
  def save(NodeConfigurationHash: Set[NodeConfigurationHash]): Box[Set[NodeId]]
}


class InMemoryNodeConfigurationHashRepository extends NodeConfigurationHashRepository {

  private[this] val repository = scala.collection.mutable.Map[NodeId, NodeConfigurationHash]()

  /**
   * Delete a node by its id
   */
  def deleteNodeConfigurations(nodeIds:Set[NodeId]) :  Box[Set[NodeId]] = {
    repository --= nodeIds
    Full(nodeIds)
  }

  /**
   * delete all node configuration
   */
  def deleteAllNodeConfigurations() : Box[Unit] = {
    val values = repository.keySet
    repository.clear

    Full(values.toSet)
  }

  /**
   * Inverse of delete: delete all node configuration not
   * given in the argument.
   */
  def onlyKeepNodeConfiguration(nodeIds:Set[NodeId]) : Box[Set[NodeId]] = {
    val remove = repository.keySet -- nodeIds
    repository --= remove
    Full(nodeIds)
  }

  def getAll() : Box[Map[NodeId, NodeConfigurationHash]] = Full(repository.toMap)

  def save(NodeConfigurationHash: Set[NodeConfigurationHash]): Box[Set[NodeId]] = {
    val toAdd = NodeConfigurationHash.map(c => (c.id, c)).toMap
    repository ++= toAdd
    Full(toAdd.keySet)
  }
}

/**
 * An implementation into LDAP
 */
class LdapNodeConfigurationHashRepository(
    rudderDit: RudderDit
  , ldapCon  : LDAPConnectionProvider[RwLDAPConnection]
) extends NodeConfigurationHashRepository with Loggable {

  import net.liftweb.json._
  import net.liftweb.json.Serialization.{ read, write }
  implicit val formats = Serialization.formats(NoTypeHints) ++ net.liftweb.json.ext.JodaTimeSerializers.all

  /**
   * Logic: there is only one object that contains all node config cache.
   * Each node config cache is stored in one value of the "nodeConfig" attribute.
   * The serialisation is simple json.
   * We won't be able to simply delete one value with that method, but it is not
   * the principal use case.
   */

  /**
   * That mapping ignore malformed node configuration and work in a
   * "best effort" way. Bad config are logged as error.
   * We fail if the entry is not of the expected type
   */
  private[this] def fromLdap(entry: Option[LDAPEntry]): LdapResult[Set[NodeConfigurationHash]] = {
    entry match {
      case None    => Set.empty[NodeConfigurationHash].success
      case Some(e) =>
        for {
          typeOk <- if(e.isA(OC_NODES_CONFIG)) {
                      "ok".success
                    } else {
                      s"Entry ${e.dn} is not a '${OC_NODES_CONFIG}', can not find node configuration caches. Entry details: ${e}".failure
                    }
        } yield {
          e.valuesFor(A_NODE_CONFIG).flatMap { json =>
            try {
              Some(read[NodeConfigurationHash](json))
            } catch {
              case e: Exception =>
                //try to get the nodeid from what should be some json
                try {
                  for {
                    JString(id) <- parse(json) \\ "id" \ "value"
                  } yield {
                    logger.info(s"Ignoring following node configuration cache info because of deserialisation error: ${id}. Policies will be regenerated for it.")
                  }
                } catch {
                  case e:Exception => //ok, that's does not seem to be json
                    logger.info(s"Ignoring an unknown node configuration cache info because of deserialisation problem.")
                }

                logger.debug(s"Faulty json and exception was: ${json}", e)

                None
            }
          }
        }
    }
  }

  private[this] def toLdap(nodeConfigs: Set[NodeConfigurationHash]): LDAPEntry = {
    val caches = nodeConfigs.map{ x => write(x) }
    val entry = rudderDit.NODE_CONFIGS.model
    entry +=! (A_NODE_CONFIG, caches.toSeq:_*)
    entry
  }


  /*
   * Delete node config matching predicate.
   * Return the list of remaining ids.
   */
  private[this] def deleteCacheMatching( shouldDeleteConfig: NodeConfigurationHash => Boolean): LdapResult[Set[NodeId]] = {
     for {
       ldap         <- ldapCon
       currentEntry <- ldap.get(rudderDit.NODE_CONFIGS.dn)
       remaining    <- fromLdap(currentEntry).map(_.filterNot(shouldDeleteConfig))
       newEntry     =  toLdap(remaining)
       saved        <- ldap.save(newEntry)
     } yield {
       remaining.map( _.id )
     }
   }

  /**
   * Delete node config by its id
   */
  def deleteNodeConfigurations(nodeIds:Set[NodeId]) :  Box[Set[NodeId]] = {
     for {
       _ <- deleteCacheMatching(nodeConfig => nodeIds.contains(nodeConfig.id))
     } yield {
       nodeIds
     }
   }.toBox

  /**
   * Inverse of delete: delete all node configuration not
   * given in the argument.
   */
  def onlyKeepNodeConfiguration(nodeIds:Set[NodeId]) : Box[Set[NodeId]] = {
     for {
       _ <- deleteCacheMatching(nodeConfig => !nodeIds.contains(nodeConfig.id))
     } yield {
       nodeIds
     }
   }.toBox


  /**
   * delete all node configuration
   */
  def deleteAllNodeConfigurations() : Box[Unit] = {
     for {
       ldap    <- ldapCon
       deleted <- ldap.delete(rudderDit.NODE_CONFIGS.dn)
       cleaned <- ldap.save(rudderDit.NODE_CONFIGS.model)
     } yield {
       ()
     }
  }.toBox


  def getAll() : Box[Map[NodeId, NodeConfigurationHash]] = {
    for {
      ldap        <- ldapCon
      entry       <- ldap.get(rudderDit.NODE_CONFIGS.dn)
      nodeConfigs <- fromLdap(entry)
    } yield {
      nodeConfigs.map(x => (x.id, x)).toMap
    }
  }.toBox

  def save(caches: Set[NodeConfigurationHash]): Box[Set[NodeId]] = {
    val updatedIds = caches.map(_.id)
    for {
      ldap          <- ldapCon
      existingEntry <- ldap.get(rudderDit.NODE_CONFIGS.dn)
      existingCache <- fromLdap(existingEntry)
      //only update and add, keep existing config cache not updated
      keptConfigs   =  existingCache.map(x => (x.id, x)).toMap.filterKeys( k => !updatedIds.contains(k) )
      entry         =  toLdap(caches ++ keptConfigs.values)
      saved         <- ldap.save(entry)
    } yield {
      updatedIds
    }
  }.toBox
}

