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


import com.normation.inventory.domain.{NodeId,AgentType}
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.exceptions.TechnicalException
import com.normation.rudder.domain.RudderDit
import com.normation.ldap.sdk._
import com.unboundid.ldap.sdk.DN
import com.normation.ldap.sdk.LDAPEntry
import com.normation.utils.Utils
import com.normation.utils.Control.sequence
import com.normation.exceptions.BusinessException
import com.normation.rudder.services.policies.VariableBuilderService
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.Constants._
import com.normation.rudder.domain.servers._
import com.normation.rudder.domain.policies.{IdentifiableCFCPI,ConfigurationRuleId}
import org.joda.time.DateTime
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.cfclerk.domain._
import net.liftweb.common._
import net.liftweb.common.Box._
import net.liftweb.util.Helpers.tryo
import com.normation.cfclerk.services.SystemVariableSpecService

class LDAPNodeConfigurationMapper(
    rudderDit                 : RudderDit
  , inventoryDit              : InventoryDit
  , systemVariableSpecService : SystemVariableSpecService
  , policyPackageService      : PolicyPackageService
  , variableBuilderService    : VariableBuilderService
  , ldap                      : LDAPConnectionProvider
) extends Loggable {
  
  
  private def setSystemVariable(variableMap : Map[String,Seq[String]]) : Map[String, Variable] = {
    var returnedMap = scala.collection.mutable.Map[String, Variable]()
    for ((variableName, values) <- variableMap) {
      try {
      	val settedVariable = SystemVariable(systemVariableSpecService.get(variableName))
      	settedVariable.values = values
      	returnedMap += (settedVariable.spec.name -> settedVariable)
      } catch {
        case e: Exception => logger.error("Could not fetch spec for system variable %s".format(variableName)) 
      }
    }
    returnedMap.toMap
  }
  
  
      //////////////////////////////    CFCPolicyInstance    //////////////////////////////

    /**
     * Utility method that maps an LDAP entry to a policy instance
     * Return a pair PolicyInstance,Boolean where the boolean stands
     * for "is the policy instance a target policy instance ?"
     */
    def toPolicyInstance(e:LDAPEntry) : Box[(IdentifiableCFCPI,Boolean)] = {
      def errorMessage(attr:String) = "Can not map entry to policy instance: missing attribute %s in entry %s".format(attr,e.dn)
      for {
        id <- {
          if(e.isA(OC_ABSTRACT_POLICY_INSTANCE)) {
            if(e.isA(OC_CR_POLICY_INSTANCE)) {
              e(A_POLICY_INSTANCE_UUID) ?~! errorMessage(A_POLICY_INSTANCE_UUID)
            } else if(e.isA(OC_TARGET_CR_POLICY_INSTANCE)){
              e(A_TARGET_POLICY_INSTANCE_UUID) ?~! errorMessage(A_TARGET_POLICY_INSTANCE_UUID)
            } else Failure("Entry %s is not mappable to policy instance (unknow policy instance type)".format(e.dn))
         } else Failure("Entry %s is not mappable to policy instance (it misses object class %s)".format(e.dn,OC_ABSTRACT_POLICY_INSTANCE))
        }
        policyId <- e(A_REFERENCE_POLICY_TEMPLATE_UUID).map(x => PolicyPackageName(x)) ?~! errorMessage(A_REFERENCE_POLICY_TEMPLATE_UUID)
        policyVersion <- for {
            attr <- e(A_REFERENCE_POLICY_TEMPLATE_VERSION) ?~! errorMessage(A_REFERENCE_POLICY_TEMPLATE_VERSION)
            v <- tryo(PolicyVersion(attr))
          } yield {
            v
          }
        crId <- e(A_CONFIGURATION_RULE_UUID) ?~! errorMessage(A_CONFIGURATION_RULE_UUID)
        policyPackage <- policyPackageService.getPolicy(PolicyPackageId(policyId,policyVersion)) ?~! "Can not found policy template '%s'".format(PolicyPackageId(policyId,policyVersion).toString)
        varSpecs = policyPackage.getAllVariableSpecs
        vared <- variableBuilderService.buildVariables(varSpecs, parsePolicyVariables(e.valuesFor(A_POLICY_VARIABLES).toSeq)) ?~! "Error when building variables from their specs and values"
        priority <- e.getAsInt(A_PRIORITY) ?~! errorMessage(A_PRIORITY)
        serial <- e.getAsInt(A_SERIAL) ?~! errorMessage(A_SERIAL)
        pi = new IdentifiableCFCPI(new ConfigurationRuleId(crId),
            new CFCPolicyInstance(CFCPolicyInstanceId(id),PolicyPackageId(policyId, policyVersion),
            //remove trackerVariableVar
            vared.filterKeys(k => k != policyPackage.trackerVariableSpec.name),
            vared.get(policyPackage.trackerVariableSpec.name).map(x => x.asInstanceOf[TrackerVariable]).getOrElse(policyPackage.trackerVariableSpec.toVariable()),
            priority,
            serial))
        
      } yield (pi, e.isA(OC_TARGET_CR_POLICY_INSTANCE))
    }

    
        //////////////////////////////    CFCServer (NodeConfiguration ?)    //////////////////////////////

    
    
  /**
   * Create a server from an LDAPTree
   * The root entry of the tree must be of type OC_RUDDER_SERVER
   * @param e
   * @return
   */
  def toNodeConfiguration(tree:LDAPTree) : Box[NodeConfiguration] = {
    if(!tree.root.isA(OC_RUDDER_SERVER)) {
      Failure("Unknow server type, or bad root for LDAPEntry tree: " + tree.root)
    } else {
      //ok, we actually have a server, start to find its policy instances (current and target)
      
      //For a policy instance in error, log the error, and delete it. It's just a cache, 
      //it will be regenerated next time (can not be much worse than completly blocking
      //deployment

      val nodePolicyInstances = tree.children.valuesIterator.flatMap { t =>
        if(t.root.isA(OC_ABSTRACT_POLICY_INSTANCE)) {
          toPolicyInstance(t.root) match {
            case Full((pi,isTarget)) => Some((pi,isTarget))
            case e:EmptyBox =>
              logger.warn(e ?~! "Error when mapping node configuration's policy instance with DN: '%s'. We are going to try to delete it".format(t.root.dn), e)
              try {
                ldap.map { con =>
                  con.delete(t.root.dn, true)
                }
              } catch {
                case e:Exception => logger.error("Can not remove the faulty node configuration's policy instance with DN: '%s': ", e)
              }
              None
          }          
        } else {
          None
        }
      }.toSeq
            
      val currentPIs = nodePolicyInstances.collect { case(pi, false) => pi }
      val targetPIs  = nodePolicyInstances.collect { case(pi, true)  => pi }
          
      //map server datas - all mandatories
      def E_MSG(attr:String, dn:DN) = "Error, missing attribute '%s' for server %s which is mandatory".format(attr,dn)
      
      // Cannot fail, the current may be empty, so return an empty value
      def getCurrentMinimalNodeConfig(e: LDAPEntry) : MinimalNodeConfig = {
          new MinimalNodeConfig(
              e(A_NAME).openOr(""),
              e(A_HOSTNAME).openOr(""),
              sequence(e.valuesFor(A_AGENTS_NAME).toSeq) { x =>
                        AgentType.fromValue(x) ?~! "Unknow value for agent type: '%s'. Authorized values are: %s".format(x, AgentType.allValues.mkString(", "))
                      } openOr(Seq()),
              e(A_NODE_POLICY_SERVER).openOr(""),
              e(A_ROOT_USER).openOr("")
          )
      }
      
      def getTargetMinimalNodeConfig(e: LDAPEntry) : Box[MinimalNodeConfig] = {
        for {
          name <- e(A_TARGET_NAME) ?~! E_MSG(A_TARGET_NAME,e.dn)
          hostname <- e(A_TARGET_HOSTNAME) ?~! E_MSG(A_TARGET_HOSTNAME,e.dn)
          agentsName <- sequence(e.valuesFor(A_TARGET_AGENTS_NAME).toSeq) { x =>
                          AgentType.fromValue(x) ?~! "Unknow value for agent type: '%s'. Authorized values are: %s".format(x, AgentType.allValues.mkString(", "))
                        }
          policyServerId <- e(A_TARGET_NODE_POLICY_SERVER) ?~! E_MSG(A_TARGET_NODE_POLICY_SERVER,e.dn)
          localAdministratorAccountName <- e(A_TARGET_ROOT_USER) ?~! E_MSG(A_TARGET_ROOT_USER,e.dn)
        } yield {
          new MinimalNodeConfig(
             name, 
             hostname,
             agentsName,
             policyServerId,
             localAdministratorAccountName
          )
        }
      }
      
      for {
        id <- tree.root()(A_NODE_UUID) ?~! E_MSG(A_NODE_UUID,tree.root.dn)
      //  modDate <- tree.root.getAsGTime(A_LAST_UPDATE_DATE) ?~! E_MSG(A_LAST_UPDATE_DATE,tree.root.dn)
        writtenDate = tree.root.getAsGTime(A_WRITTEN_DATE)
        targetNodeConfig <- getTargetMinimalNodeConfig(tree.root()) ?~! "Missing target node minimal configuration"
      } yield {
        if(tree.root.isA(OC_ROOT_POLICY_SERVER)) {
          val server = new RootNodeConfiguration(id, currentPIs,targetPIs, true, 
              	getCurrentMinimalNodeConfig(tree.root()), 
              	targetNodeConfig, 
              	writtenDate.map(_.dateTime),
              	setSystemVariable(parsePolicyVariables(tree.root().valuesFor(A_NODE_CONFIGURATION_SYSTEM_VARIABLE).toSeq)),
              	setSystemVariable(parsePolicyVariables(tree.root().valuesFor(A_NODE_CONFIGURATION_TARGET_SYSTEM_VARIABLE).toSeq))
              	)
 
          server
        } else {
          val server = new SimpleNodeConfiguration(id, currentPIs,targetPIs, tree.root().getAsBoolean(A_IS_POLICY_SERVER).getOrElse(false), 
              getCurrentMinimalNodeConfig(tree.root()), 
              targetNodeConfig, 
              writtenDate.map(_.dateTime),
              setSystemVariable(parsePolicyVariables(tree.root().valuesFor(A_NODE_CONFIGURATION_SYSTEM_VARIABLE).toSeq)),
              setSystemVariable(parsePolicyVariables(tree.root().valuesFor(A_NODE_CONFIGURATION_TARGET_SYSTEM_VARIABLE).toSeq))          
          )
 
          server
        }
      }

    }
  }

  /**
   * Map a node to a list of LDAPEntries. 
   * These entries may be latter persisted.
   * 
   * Be careful to cleanly specialize objectType if 
   * the node is a root server
   */
  def fromNodeConfiguration(server:NodeConfiguration) : LDAPTree = {
    
    /**
     * Create policy instance entries in the context of a server entry.
     * Note that contrary to Roles, policy instances could be 
     * split from a server (it is only used to build the parent dn
     * of these entries)
     */
    def fromPolicyInstance(identifiable:IdentifiableCFCPI,  serverEntry:LDAPEntry, isCurrent:Boolean) : LDAPEntry = {
      
      val entry =
        if(isCurrent) rudderDit.RUDDER_NODES.SERVER.POLICY_INSTANCE.model(identifiable.policyInstance.id.value, serverEntry.dn)
        else rudderDit.RUDDER_NODES.SERVER.TARGET_POLICY_INSTANCE.model(identifiable.policyInstance.id.value, serverEntry.dn)
      
      val vars = identifiable.policyInstance.getVariables().values.toSeq :+ identifiable.policyInstance.TrackerVariable
      entry +=! (A_CONFIGURATION_RULE_UUID, identifiable.configurationRuleId.value)

      entry +=! (A_REFERENCE_POLICY_TEMPLATE_UUID, identifiable.policyInstance.policyId.name.value)
      entry +=! (A_REFERENCE_POLICY_TEMPLATE_VERSION, identifiable.policyInstance.policyId.version.toString)
      entry +=! (A_LAST_UPDATE_DATE, GeneralizedTime(identifiable.policyInstance.modificationDate).toString)
      //put variable under Map[String, Seq[String]]
      /*val variables = policyInstance.getVariables().map { case(name,v) =>
        (name, v.values)
      }.toMap*/
      entry +=! (A_POLICY_VARIABLES, variableToSeq(vars):_*)
      entry +=! (A_SERIAL, identifiable.policyInstance.serial.toString)
      entry +=! (A_PRIORITY, identifiable.policyInstance.priority.toString)
      entry
    }
    
    //if server id is null or empty, throw an error here
    val id = NodeId(Utils.??!(server.id).getOrElse(throw new BusinessException("NodeConfiguration UUID can not be null nor emtpy")))
    
    //Build the server entry and its children (role an policy instances)
    val serverEntry : LDAPEntry = server match {
      case rootNodeConfiguration:RootNodeConfiguration => rudderDit.RUDDER_NODES.SERVER.rootPolicyServerModel(id)
      case s => rudderDit.RUDDER_NODES.SERVER.nodeConfigurationModel(id)
    }
    serverEntry +=!(A_SERVER_IS_MODIFIED, server.isModified.toLDAPString)
    //serverEntry +=!(A_LAST_UPDATE_DATE, GeneralizedTime( Utils.??(server.modificationDate).getOrElse(new DateTime) ).toString)

    if (server.writtenDate != None)
      serverEntry +=!(A_WRITTEN_DATE, GeneralizedTime( server.writtenDate.open_! ).toString)

    serverEntry +=!(A_NAME, server.currentMinimalNodeConfig.name)
    serverEntry +=!(A_HOSTNAME, server.currentMinimalNodeConfig.hostname)
    serverEntry +=!(A_NODE_POLICY_SERVER, server.currentMinimalNodeConfig.policyServerId)
    serverEntry +=!(A_ROOT_USER, server.currentMinimalNodeConfig.localAdministratorAccountName)
    serverEntry +=!(A_AGENTS_NAME, server.currentMinimalNodeConfig.agentsName.map( _.toString ):_*)

    serverEntry +=!(A_TARGET_NAME, server.targetMinimalNodeConfig.name)
    serverEntry +=!(A_TARGET_HOSTNAME, server.targetMinimalNodeConfig.hostname)
    serverEntry +=!(A_TARGET_NODE_POLICY_SERVER, server.targetMinimalNodeConfig.policyServerId)
    serverEntry +=!(A_TARGET_ROOT_USER, server.targetMinimalNodeConfig.localAdministratorAccountName)
    serverEntry +=!(A_TARGET_AGENTS_NAME, server.targetMinimalNodeConfig.agentsName.map( _.toString ):_*)

    serverEntry +=!(A_IS_POLICY_SERVER, server.isPolicyServer.toLDAPString )
    
    // Add the system variable
    serverEntry +=!(A_NODE_CONFIGURATION_SYSTEM_VARIABLE, variableToSeq(server.getCurrentSystemVariables().values.toSeq):_*)
    serverEntry +=!(A_NODE_CONFIGURATION_TARGET_SYSTEM_VARIABLE, variableToSeq(server.getTargetSystemVariables().values.toSeq):_*)
    
    //should be ok, that's why we use an exception
    LDAPTree(
      Seq(serverEntry) ++  //server root entry
      { server.getCurrentPolicyInstances.map( pi => fromPolicyInstance(pi._2, serverEntry, true)) } ++
      { server.getPolicyInstances.map( pi => fromPolicyInstance(pi._2, serverEntry, false)) }
    ) match {
      case Full(tree) => tree
      case e:EmptyBox => 
        val msg = "Can not build a tree of LDAPEntries for server %s".format(server.id)
        logger.error(msg, e)
        throw new TechnicalException(msg)
    }
  }
    
}