/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.services.policies.nodeconfig

import com.normation.rudder.domain.policies.RuleId
import com.normation.cfclerk.domain.Cf3PolicyDraftId
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.inventory.domain.NodeId
import net.liftweb.common.Box
import net.liftweb.common.Full
import org.joda.time.DateTime
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.{OC_NODES_CONFIG, A_NODE_CONFIG}
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.ldap.sdk.LDAPEntry
import net.liftweb.common.Failure
import net.liftweb.common.Loggable
import com.normation.cfclerk.domain.Variable


case class PolicyCache(
    ruleId    : RuleId
  , draftId   : Cf3PolicyDraftId
  , cacheValue: Int
)

case class NodeConfigurationCache(
    id: NodeId
  , writtenDate: Option[DateTime]
  , nodeInfoCache: Int
  , parameterCache: Int
  , nodeContextCache: Int
  , policyCache: Set[PolicyCache]

)

/*
 * That object should be a service, no ?
 */
object NodeConfigurationCache {

  def apply(nodeConfig: NodeConfiguration): NodeConfigurationCache = {


    /*
     * Compute the hash cash value for a set of variables:
     * - the order does not matter
     * - duplicate (same name) variable make no sense, so we remove them
     * - only the ORDERED values matters
     *
     * Also, variable with no values are equivalent to no variable, so we
     * remove them.
     */
    def variablesToHash(variables: Iterable[Variable]): Int = {
      val z = variables.map( x => (x.spec.name, x.values) ).filterNot( _._2.isEmpty ).toSet
      z.hashCode
    }


    /*
     * for node info, only consider:
     * name, hostname, agentsName, policyServerId, localAdministratorAccountName
     */
    val nodeInfoCacheValue = {
      val i = nodeConfig.nodeInfo
      List(
        i.name.hashCode
      , i.hostname.hashCode
      , i.agentsName.hashCode
      , i.policyServerId.hashCode
      , i.localAdministratorAccountName.hashCode
      ).hashCode
    }

    /*
     * For policy draft, we want to check
     * - technique name / version
     * - variables
     * - seriale
     */
    val policyCacheValue = {
      nodeConfig.policyDrafts.map { case r:RuleWithCf3PolicyDraft =>
        PolicyCache(
            r.ruleId
          , r.draftId
          , r.cf3PolicyDraft.serial + r.cf3PolicyDraft.technique.id.hashCode + variablesToHash(r.cf3PolicyDraft.variableMap.values)
        )
      }.toSet
    }

    /*
     * For parameters, do not consider:
     * - overridable
     * - description
     * But as ParameterForConfiguration only has relevant information,
     * we can simply take the hashcode.
     * Also, don't depend of the datastructure used, nor
     * of the order of the variables (so is set is good ?)
     */
    val parameterCache: Int = {
      nodeConfig.parameters.hashCode
    }

    /*
     * System variables are variables.
     */
    val nodeContextCache: Int = {
      variablesToHash(nodeConfig.nodeContext.values)
    }

    new NodeConfigurationCache(
        id = nodeConfig.nodeInfo.id
      , writtenDate = nodeConfig.writtenDate
      , nodeInfoCache = nodeInfoCacheValue
      , parameterCache = parameterCache
      , nodeContextCache = nodeContextCache
      , policyCache = policyCacheValue
    )
  }
}


/**
 * A class that keep minimum information
 * to track changement in node configuration.
 */
trait NodeConfigurationCacheRepository {

  /**
   * Delete node config by its id
   */
  def deleteNodeConfigurations(nodeIds:Set[NodeId]) :  Box[Set[NodeId]]

  /**
   * delete all node configuration
   */
  def deleteAllNodeConfigurations() : Box[Unit]

  /**
   * Inverse of delete: delete all node configuration not
   * given in the argument.
   */
  def onlyKeepNodeConfiguration(nodeIds:Set[NodeId]) : Box[Set[NodeId]]

  def getAll() : Box[Map[NodeId, NodeConfigurationCache]]

  def save(nodeConfigurationCache: Set[NodeConfigurationCache]): Box[Set[NodeId]]
}


class InMemoryNodeConfigurationCacheRepository extends NodeConfigurationCacheRepository {

  private[this] val repository = scala.collection.mutable.Map[NodeId, NodeConfigurationCache]()

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

  def getAll() : Box[Map[NodeId, NodeConfigurationCache]] = Full(repository.toMap)

  def save(nodeConfigurationCache: Set[NodeConfigurationCache]): Box[Set[NodeId]] = {
    val toAdd = nodeConfigurationCache.map(c => (c.id, c)).toMap
    repository ++= toAdd
    Full(toAdd.keySet)
  }
}

/**
 * An implementation into LDAP
 */
class LdapNodeConfigurationCacheRepository(
    rudderDit: RudderDit
  , ldapCon  : LDAPConnectionProvider[RwLDAPConnection]
) extends NodeConfigurationCacheRepository with Loggable {

  import net.liftweb.json._
  import net.liftweb.json.Serialization.{read, write}
  implicit val formats = Serialization.formats(NoTypeHints)

  /*
   * Logic: there is only one object that contains all node config cache.
   * Each node config cache is store in one value of the "nodeConfig" attribute.
   * The serialisation is simple json.
   * We won't be able to simply delete one value with that method, but it is not
   * the principal use case.
   */

  /**
   * That mapping ignore malformed node configuration and work in a
   * "best effort" way. Bad config are logged as error.
   * We fail if the entry is not of the expected type
   */
  private[this] def fromLdap(e:LDAPEntry): Box[Set[NodeConfigurationCache]] = {
     for {
       typeOk <- if(e.isA(OC_NODES_CONFIG)) {
                   Full("ok")
                 } else {
                   Failure(s"Entry ${e.dn} is not a '${OC_NODES_CONFIG}', can not find node configuration caches. Entry details: ${e}")
                 }
     } yield {
       e.valuesFor(A_NODE_CONFIG).flatMap { json =>
         try {
           Some(read[NodeConfigurationCache](json))
         } catch {
           case e: Exception =>
             logger.warn(s"Ignoring following node configuration cache info because of deserialisation error: ${json}", e)
             None
         }
       }
     }
  }

  private[this] def toLdap(nodeConfigs: Set[NodeConfigurationCache]): LDAPEntry = {
    val caches = nodeConfigs.map{ x => write(x) }
    val entry = rudderDit.NODE_CONFIGS.model
    entry +=! (A_NODE_CONFIG, caches.toSeq:_*)
    entry
  }


  /*
   * Delete node config matching predicate.
   * Return the list of remaining ids.
   */
  private[this] def deleteCacheMatching( shouldDeleteConfig: NodeConfigurationCache => Boolean): Box[Set[NodeId]] = {
     for {
       ldap         <- ldapCon
       currentEntry <- ldap.get(rudderDit.NODE_CONFIGS.dn)
       nodeConfigs  <- fromLdap(currentEntry)
       remaining    =  nodeConfigs.filterNot(shouldDeleteConfig)
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
   }

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
   }


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
  }


  def getAll() : Box[Map[NodeId, NodeConfigurationCache]] = {
    for {
      ldap    <- ldapCon
      entry   <- ldap.get(rudderDit.NODE_CONFIGS.dn)
      nodeConfigs <- fromLdap(entry)
    } yield {
      nodeConfigs.map(x => (x.id, x)).toMap
    }
  }

  def save(caches: Set[NodeConfigurationCache]): Box[Set[NodeId]] = {
    for {
      ldap  <- ldapCon
      entry =  toLdap(caches.toSet)
      saved <- ldap.save(entry)
    } yield {
      caches.map( _.id )
    }
  }
}

