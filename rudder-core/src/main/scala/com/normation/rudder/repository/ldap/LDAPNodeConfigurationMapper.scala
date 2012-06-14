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
import com.normation.rudder.domain.policies.{RuleWithCf3PolicyDraft,RuleId}
import org.joda.time.DateTime
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.domain._
import net.liftweb.common._
import net.liftweb.common.Box._
import net.liftweb.util.Helpers.tryo
import com.normation.cfclerk.services.SystemVariableSpecService

class LDAPNodeConfigurationMapper(
    rudderDit                 : RudderDit
  , inventoryDit              : InventoryDit
  , systemVariableSpecService : SystemVariableSpecService
  , techniqueRepository       : TechniqueRepository
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
  
  
      //////////////////////////////    Cf3PolicyDraft    //////////////////////////////

    /**
     * Utility method that maps an LDAP entry to a directive
     * Return a pair Directive,Boolean where the boolean stands
     * for "is the directive a target directive ?"
     */
    def toRuleWithCf3PolicyDraft(e:LDAPEntry) : Box[(RuleWithCf3PolicyDraft,Boolean)] = {
      def errorMessage(attr:String) = "Can not map entry to directive: missing attribute %s in entry %s".format(attr,e.dn)
      for {
        id <- {
          if(e.isA(OC_ABSTRACT_RULE_WITH_CF3POLICYDRAFT)) {
            if(e.isA(OC_RULE_WITH_CF3POLICYDRAFT)) {
              e(A_DIRECTIVE_UUID) ?~! errorMessage(A_DIRECTIVE_UUID)
            } else if(e.isA(OC_TARGET_RULE_WITH_CF3POLICYDRAFT)){
              e(A_TARGET_DIRECTIVE_UUID) ?~! errorMessage(A_TARGET_DIRECTIVE_UUID)
            } else Failure("Entry %s is not mappable to directive (unknow directive type)".format(e.dn))
         } else Failure("Entry %s is not mappable to directive (it misses object class %s)".format(e.dn,OC_ABSTRACT_RULE_WITH_CF3POLICYDRAFT))
        }
        techniqueId <- e(A_TECHNIQUE_UUID).map(x => TechniqueName(x)) ?~! errorMessage(A_TECHNIQUE_UUID)
        policyVersion <- for {
            attr <- e(A_TECHNIQUE_VERSION) ?~! errorMessage(A_TECHNIQUE_VERSION)
            v <- tryo(TechniqueVersion(attr))
          } yield {
            v
          }
        ruleId <- e(A_RULE_UUID) ?~! errorMessage(A_RULE_UUID)
        policyPackage <- techniqueRepository.get(TechniqueId(techniqueId,policyVersion)) ?~! "Can not found technique '%s'".format(TechniqueId(techniqueId,policyVersion).toString)
        varSpecs = policyPackage.getAllVariableSpecs
        vared <- variableBuilderService.buildVariables(varSpecs, parsePolicyVariables(e.valuesFor(A_DIRECTIVE_VARIABLES).toSeq)) ?~! "Error when building variables from their specs and values"
        priority <- e.getAsInt(A_PRIORITY) ?~! errorMessage(A_PRIORITY)
        serial <- e.getAsInt(A_SERIAL) ?~! errorMessage(A_SERIAL)
        directive = new RuleWithCf3PolicyDraft(new RuleId(ruleId),
            new Cf3PolicyDraft(Cf3PolicyDraftId(id),TechniqueId(techniqueId, policyVersion),
            //remove trackerVariableVar
            vared.filterKeys(k => k != policyPackage.trackerVariableSpec.name),
            vared.get(policyPackage.trackerVariableSpec.name).map(x => x.asInstanceOf[TrackerVariable]).getOrElse(policyPackage.trackerVariableSpec.toVariable()),
            priority,
            serial))
        
      } yield (directive, e.isA(OC_TARGET_RULE_WITH_CF3POLICYDRAFT))
    }

    
        //////////////////////////////    CFCNode (NodeConfiguration ?)    //////////////////////////////

    
    
  /**
   * Create a server from an LDAPTree
   * The root entry of the tree must be of type OC_NODE_CONFIGURATION
   * @param e
   * @return
   */
  def toNodeConfiguration(tree:LDAPTree) : Box[NodeConfiguration] = {
    if(!tree.root.isA(OC_NODE_CONFIGURATION)) {
      Failure("Unknow server type, or bad root for LDAPEntry tree: " + tree.root)
    } else {
      //ok, we actually have a server, start to find its directives (current and target)
      
      //For a directive in error, log the error, and delete it. It's just a cache, 
      //it will be regenerated next time (can not be much worse than completly blocking
      //deployment

      val nodeDirectives = tree.children.valuesIterator.flatMap { t =>
        if(t.root.isA(OC_ABSTRACT_RULE_WITH_CF3POLICYDRAFT)) {
          toRuleWithCf3PolicyDraft(t.root) match {
            case Full((directive,isTarget)) => Some((directive,isTarget))
            case e:EmptyBox =>
              logger.warn(e ?~! "Error when mapping node configuration's directive with DN: '%s'. We are going to try to delete it".format(t.root.dn), e)
              try {
                ldap.map { con =>
                  con.delete(t.root.dn, true)
                }
              } catch {
                case e:Exception => logger.error("Can not remove the faulty node configuration's directive with DN: '%s': ", e)
              }
              None
          }          
        } else {
          None
        }
      }.toSeq
            
      val currentPIs = nodeDirectives.collect { case(directive, false) => directive }
      val targetPIs  = nodeDirectives.collect { case(directive, true)  => directive }
          
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
     * Create directive entries in the context of a server entry.
     * Note that contrary to Roles, directives could be 
     * split from a server (it is only used to build the parent dn
     * of these entries)
     */
    def fromDirective(identifiable:RuleWithCf3PolicyDraft,  serverEntry:LDAPEntry, isCurrent:Boolean) : LDAPEntry = {
      
      val entry =
        if(isCurrent) rudderDit.NODE_CONFIGS.NODE_CONFIG.CF3POLICYDRAFT.model(identifiable.cf3PolicyDraft.id.value, serverEntry.dn)
        else rudderDit.NODE_CONFIGS.NODE_CONFIG.TARGET_CF3POLICYDRAFT.model(identifiable.cf3PolicyDraft.id.value, serverEntry.dn)
      
      val vars = identifiable.cf3PolicyDraft.getVariables().values.toSeq :+ identifiable.cf3PolicyDraft.trackerVariable
      entry +=! (A_RULE_UUID, identifiable.ruleId.value)

      entry +=! (A_TECHNIQUE_UUID, identifiable.cf3PolicyDraft.techniqueId.name.value)
      entry +=! (A_TECHNIQUE_VERSION, identifiable.cf3PolicyDraft.techniqueId.version.toString)
      entry +=! (A_LAST_UPDATE_DATE, GeneralizedTime(identifiable.cf3PolicyDraft.modificationDate).toString)
      //put variable under Map[String, Seq[String]]
      /*val variables = directive.getVariables().map { case(name,v) =>
        (name, v.values)
      }.toMap*/
      entry +=! (A_DIRECTIVE_VARIABLES, variableToSeq(vars):_*)
      entry +=! (A_SERIAL, identifiable.cf3PolicyDraft.serial.toString)
      entry +=! (A_PRIORITY, identifiable.cf3PolicyDraft.priority.toString)
      entry
    }
    
    //if server id is null or empty, throw an error here
    val id = NodeId(Utils.??!(server.id).getOrElse(throw new BusinessException("NodeConfiguration UUID can not be null nor emtpy")))
    
    //Build the server entry and its children (role an directives)
    val serverEntry : LDAPEntry = server match {
      case rootNodeConfiguration:RootNodeConfiguration => rudderDit.NODE_CONFIGS.NODE_CONFIG.rootPolicyServerModel(id)
      case s => rudderDit.NODE_CONFIGS.NODE_CONFIG.nodeConfigurationModel(id)
    }
    serverEntry +=!(A_SERVER_IS_MODIFIED, server.isModified.toLDAPString)
    //serverEntry +=!(A_LAST_UPDATE_DATE, GeneralizedTime( Utils.??(server.modificationDate).getOrElse(DateTime.now) ).toString)

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
      { server.getCurrentDirectives.map( directive => fromDirective(directive._2, serverEntry, true)) } ++
      { server.getDirectives.map( directive => fromDirective(directive._2, serverEntry, false)) }
    ) match {
      case Full(tree) => tree
      case e:EmptyBox => 
        val msg = "Can not build a tree of LDAPEntries for server %s".format(server.id)
        logger.error(msg, e)
        throw new TechnicalException(msg)
    }
  }
    
}