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

package com.normation.rudder.repository.ldap

import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.repository.NodeConfigurationRepository
import com.unboundid.ldap.sdk.DN
import com.normation.rudder.exceptions.HierarchicalException
import com.normation.rudder.domain.servers.{RootNodeConfiguration,NodeConfiguration,SimpleNodeConfiguration}
import com.normation.rudder.domain.{RudderDit,Constants}
import com.normation.ldap.sdk.LDAPConnectionProvider
import net.liftweb.common._
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.inventory.domain._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.cfclerk.domain.TechniqueId
import com.normation.utils.Control.bestEffort

class LDAPNodeConfigurationRepository(
    ldap:LDAPConnectionProvider[RwLDAPConnection],
    rudderDit:RudderDit,
    mapper:LDAPNodeConfigurationMapper
) extends NodeConfigurationRepository with Loggable {



  def addRootNodeConfiguration(rootNodeConfiguration : RootNodeConfiguration) : Box[RootNodeConfiguration] = saveNodeConfiguration(rootNodeConfiguration).map(_ => rootNodeConfiguration)

  /**
   * An utility class that finds all root NodeConfigurations UUID among servers.
   * It should alway return a full seq of exactly one server
   */
  def getRootNodeIds() : Box[Seq[NodeId]] = {
    ldap.map { con =>
      con.searchOne(rudderDit.NODE_CONFIGS.dn, IS(OC_ROOT_POLICY_SERVER), "1:1" ).collect {
        case e if(rudderDit.NODE_CONFIGS.NODE_CONFIG.idFromDn(e.dn).isDefined) => rudderDit.NODE_CONFIGS.NODE_CONFIG.idFromDn(e.dn).get
      }
    }
  }

  /**
   * retrieve server object from a set of dn of server Entries.
   * It checks that the dn is actually a dn for a server entry
   * It only returns if all dn which are server entry DN lead to
   * a server object in order to not miss data inconsistencies (what
   * means that even if only one server is corrupted, none could b retrieve)
   * TODO: discuss if it's the behaviour we want
   * @param con
   * @param dns
   * @return
   */
  private def findNodeConfigurationFromNodeConfigurationEntryDN(con:RwLDAPConnection, dns:Set[DN]) : Box[List[NodeConfiguration]] = {
    ( (Full(List[NodeConfiguration]()):Box[List[NodeConfiguration]]) /: dns  ) {
      case (e:EmptyBox,_) => e
      case (Full(list),dn) =>
        if(!dn.getRDN.isMultiValued && dn.getRDN.hasAttribute(rudderDit.NODE_CONFIGS.NODE_CONFIG.rdnAttribute._1)) {
          (for {
            tree <- con.getTree(dn)
            server <- mapper.toNodeConfiguration(tree)
          } yield server) match {
            case e:EmptyBox => logger.error("Couldn't map node %s reason : %s". format(dn, e)) ; e
            case Full(server) => Full(server::list)
          }
        } else Full(list)
    }
  }

  def getRootNodeConfiguration() : Box[RootNodeConfiguration] = {
    ldap.flatMap { con =>
      val seq = con.searchOne(rudderDit.NODE_CONFIGS.dn, IS(OC_ROOT_POLICY_SERVER), "1:1" )

      if(seq.size == 1) {
        findNodeConfigurationFromNodeConfigurationEntryDN(con,Set(seq(0).dn)) match {
          case Full(seq2) => seq2.toList match {
            case (root:RootNodeConfiguration) :: Nil => Full(root)
            case s :: Nil => Failure("Corrupted data: found a normal server in place of the root server %s".format(s))
            case other => Failure("Exactly one policy server must be configured, found %s (%s)".format(other.size, other.map(_.id).mkString(",")))
          }
          case e:EmptyBox => e
        }
      } else if(seq.size < 1)
        Failure("Exactly one policy server must be configured, found 0")
      else Failure("Exactly one policy server must be configured, found %s (%s)".format(seq.size, seq.map(_.dn).mkString(",")))
    }
  }


  /**
   * Search a server by its id
   * @param id
   * @return the server
   */
  def findNodeConfiguration(id : NodeId) : Box[NodeConfiguration] = {
      for {
        con <- ldap
        tree <- con.getTree(rudderDit.NODE_CONFIGS.NODE_CONFIG.dn(id.value))
        server <- mapper.toNodeConfiguration(tree) ?~! "Mapping from LDAP representation to NodeConfiguration failed"
      } yield server
  }



  /**
   * Return multiples servers
   * @param ids
   * @return
   */
  def getMultipleNodeConfigurations(ids : Seq[NodeId]) : Box[Set[NodeConfiguration]] = {
    ldap.flatMap { con =>
      findNodeConfigurationFromNodeConfigurationEntryDN(con,
        con.searchOne(
          rudderDit.NODE_CONFIGS.dn,
          OR(ids.toSet[NodeId].map(id => EQ(rudderDit.NODE_CONFIGS.NODE_CONFIG.rdnAttribute._1,id.value)).toSeq:_*),
          "1:1"
        ).map(_.dn).toSet
      ).map(_.toSet)
    }
  }

  /**
   * Save a server in the repo
   * @param server
   * @return
   */
  def saveNodeConfiguration(server:NodeConfiguration) : Box[NodeConfiguration] = {
    (ldap.map { con =>
      val tree = mapper.fromNodeConfiguration(server)
      con.saveTree(tree, true)
    }).map( _ => server)
  }


  /**
   * Save several servers in the repo
   * @param server
   * @return
   */
  def saveMultipleNodeConfigurations(servers: Seq[NodeConfiguration]) : Box[Seq[NodeConfiguration]] = {
   ( (Full(Nil):Box[List[NodeConfiguration]]) /: servers ) {
     case (e:EmptyBox, _) => e
     case (Full(list), server) => this.saveNodeConfiguration(server) match {
       case Full(s) => Full(s::list)
       case e:EmptyBox => e
     }
   }
  }

  /**
   * Delete a server. It will first clean its roles, and keep the consistencies of data
   * @param server
   */
  def deleteNodeConfiguration(server:NodeConfiguration) : Box[String] = deleteNodeConfiguration(server.id)

  /**
   * Delete a node configuration.
   * Does not check the consistency of anything
   * @param server
   */
  def deleteNodeConfiguration(id:String) : Box[String] = {
    for {
      con <- ldap
      deleted <- con.delete(rudderDit.NODE_CONFIGS.NODE_CONFIG.dn(id), true)
    } yield {
      id
    }
  }

  /**
   * Delete all node configurations
   */
  def deleteAllNodeConfigurations : Box[Set[NodeId]] = {
    for {
      con           <- ldap
      nodeConfigDns <- Full(con.searchOne(rudderDit.NODE_CONFIGS.dn, ALL, "1:1").map(_.dn))
      deleted       <- bestEffort(nodeConfigDns) { dn =>
                         con.delete(dn, recurse = true )
                       }
    } yield {
      nodeConfigDns.flatMap { dn =>
        rudderDit.NODE_CONFIGS.NODE_CONFIG.idFromDn(dn)
      }.toSet
    }
  }


  /**
   * Return all servers
   * @return
   */
  def getAll() : Box[Map[String, NodeConfiguration]] = {
    ldap.flatMap { con =>
      findNodeConfigurationFromNodeConfigurationEntryDN(con, con.searchOne(rudderDit.NODE_CONFIGS.dn, ALL, "1:1").map(_.dn).toSet)
    }.map { seq => seq.map(s => (s.id,s)).toMap }
  }

   /**
   * Look for all server which have the given directive ID.
   */
  def findNodeConfigurationByCurrentRuleId(id:RuleId) : Box[Seq[NodeConfiguration]] = {
    ldap.flatMap { con =>
      findNodeConfigurationFromNodeConfigurationEntryDN(con,
        con.searchSub(
          rudderDit.NODE_CONFIGS.dn,
          AND(IS(OC_NODE_CONFIGURATION),EQ(A_DIRECTIVE_UUID,id.value)),
          "1:1"
        ).map { e => e.dn.getParent }.toSet
      )
    }
  }

  /**
   * Look for all server which have the given policy name (however directive
   * of that policy they, as long as they have at least one)
   */
  def findNodeConfigurationByTargetPolicyName(techniqueId:TechniqueId) : Box[Seq[NodeConfiguration]]  = {
    ldap.flatMap { con =>
      findNodeConfigurationFromNodeConfigurationEntryDN(con,
        con.searchSub(
          rudderDit.NODE_CONFIGS.dn,
          AND(IS(OC_TARGET_RULE_WITH_CF3POLICYDRAFT),EQ(A_NAME,techniqueId.name.value),EQ(A_TECHNIQUE_VERSION,techniqueId.version.toString)),
          "1:1"
        ).map { e => e.dn.getParent }.toSet
      )
    }
  }


  /**
   * Return all the server that need to be commited
   * Meaning, all servers that have a difference between the current and target directive
   *
   * TODO: perhaps it should be a method of BridgeToCfclerkService,
   * and then NodeConfigurationService will be able to find all servers with
   * theses directives
   */
  def findUncommitedNodeConfigurations() : Box[Seq[NodeConfiguration]] = {
    ldap.flatMap { con =>
      findNodeConfigurationFromNodeConfigurationEntryDN(con,
        con.searchOne(
          rudderDit.NODE_CONFIGS.dn,
          AND(IS(OC_NODE_CONFIGURATION),EQ(A_SERVER_IS_MODIFIED,true.toLDAPString)),
          "1:1"
        ).map(_.dn).toSet
      )
    }
  }

}

