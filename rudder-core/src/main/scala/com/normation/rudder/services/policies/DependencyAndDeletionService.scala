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

package com.normation.rudder.services.policies

import com.normation.rudder.domain.policies.{
  GroupTarget,PolicyInstanceTarget
}
import com.unboundid.ldap.sdk.DN
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.ConfigurationRule
import net.liftweb.common._
import com.normation.rudder.domain.policies.{UserPolicyTemplateId, ConfigurationRuleId, PolicyInstanceId}
import com.normation.rudder.domain.{RudderLDAPConstants, RudderDit}
import RudderLDAPConstants._
import com.normation.utils.Control.sequence
import com.normation.ldap.sdk.{ReadOnlyLDAPConnection, BuildFilter, LDAPConnectionProvider}
import BuildFilter._
import com.normation.rudder.repository._
import com.normation.eventlog.EventActor
import com.normation.utils.HashcodeCaching


/**
 * A container for items which depend on policy instances
 */
case class PolicyInstanceDependencies(
  policyInstanceId:PolicyInstanceId,
  configurationRules:Seq[ConfigurationRule]
) extends HashcodeCaching 

/**
 * A container for items which depend on policy instances
 */
case class TargetDependencies(
  target:PolicyInstanceTarget,
  configurationRules:Seq[ConfigurationRule]
) extends HashcodeCaching 

/**
 * A container for items which depend on policy template
 * For now, we don't care of policy instance <-> configuration rules
 */
case class PolicyTemplateDependencies(
  userPolicyTemplateId:UserPolicyTemplateId,
  policyInstances:Map[PolicyInstanceId, (PolicyInstance,Seq[ConfigurationRuleId])],
  configurationRules:Map[ConfigurationRuleId,ConfigurationRule]
) extends HashcodeCaching 


sealed trait ModificationStatus
case object DontCare extends ModificationStatus
case object OnlyEnableable extends ModificationStatus
case object OnlyDisableable extends ModificationStatus

/**
 *
 * This trait deals with dependency between items and
 * cascade deletion of items.
 *
 * Ex: if we want to delete a group, we have to delete
 * all configuration rules which have that group as target.
 */
trait DependencyAndDeletionService {

  /**
   * Find all Configuration rules that depend on that
   * policy instance.
   * onlyForState allows to filter dependencies based on the new status
   * they should have if the parent become of a given status.
   * For example, if <code>onlyForState</code> is set to OnlyEnableable,
   * that method only return dependent items which will switch from disabled to enabled 
   * if that policyInstance was switching from disabled to enabled 
   * (independently from the actual status of that policy instance).
   * The DontCare ModificationStatus does not filter. 
   */
  def policyInstanceDependencies(id:PolicyInstanceId, onlyForState:ModificationStatus = DontCare) : Box[PolicyInstanceDependencies]

  /**
   * Delete a given item and modify all objects that depends on it.
   * The actual action on objects depend of their use of the item, and can 
   * be: delete item, make the item no more use that policy instance, etc. 
   * Return the list of items actually modified.
   */
  def cascadeDeletePolicyInstance(id:PolicyInstanceId, actor:EventActor) : Box[PolicyInstanceDependencies]

  /**
   * Find all Configuration rules and policy isntances that depend on that
   * policy template.
   * onlyForState allows to filter dependencies based on the new status
   * they should have if the parent become of a given status.
   * For example, if <code>onlyForState</code> is set to OnlyEnableable,
   * that method only return dependent items which will switch from disabled to enabled 
   * if that policy template was switching from disabled to enabled 
   * (independently from the actual status of that policy template).
   * The DontCare ModificationStatus does not filter. 
   */
  def policyTemplateDependencies(id:UserPolicyTemplateId, onlyForState:ModificationStatus = DontCare) : Box[PolicyTemplateDependencies]

  /**
   * Delete a given item and modify all objects that depends on it.
   * The actual action on objects depend of their use of the item, and can 
   * be: delete item, make the item no more use that policy instance, etc. 
   * Return the list of items actually modified.
   */
  def cascadeDeletePolicyTemplate(id:UserPolicyTemplateId, actor:EventActor) : Box[PolicyTemplateDependencies]

  /**
   * Find all Configuration rules that depend on that
   * ptarget.
   * If onlyEnableable is set to true, that method only return
   * dependent item which will switch from disabled to enabled 
   * if that target was switching from disabled to enabled 
   * (independently from the actual status of that target).
   */
  def targetDependencies(target:PolicyInstanceTarget, onlyEnableable:Boolean = true) : Box[TargetDependencies]

  /**
   * Delete a given item and modify all objects that depends on it.
   * The actual action on objects depend of their use of the item, and can 
   * be: delete item, make the item no more use that policy instance, etc. 
   * Return the list of items actually modified.
   */
  def cascadeDeleteTarget(target:PolicyInstanceTarget, actor:EventActor) : Box[TargetDependencies]
  
  
}



///////////////////////////////// default implementation /////////////////////////////////

class DependencyAndDeletionServiceImpl(
    ldap                       : LDAPConnectionProvider
  , rudderDit                  : RudderDit
  , policyInstanceRepository   : PolicyInstanceRepository
  , policyTemplateRepository   : UserPolicyTemplateRepository
  , configurationRuleRepository: ConfigurationRuleRepository
  , groupRepository            : NodeGroupRepository
  , mapper                     : LDAPEntityMapper
  , targetService              : PolicyInstanceTargetService
) extends DependencyAndDeletionService with Loggable {

  /**
   * Utility method that find configuration rules which depends upon a policy instance. 
   * Some configuration rules may be omited
   */
  private[this] def searchConfigurationRules(
      con:ReadOnlyLDAPConnection
    , id:PolicyInstanceId
  ):Box[Seq[ConfigurationRule]] = {
    sequence(con.searchOne(rudderDit.CONFIG_RULE.dn, EQ(A_WBPI_UUID, id.value))) { entry =>
      mapper.entry2ConfigurationRule(entry)
    }
  }
  

  /**
   * utility method to call if only enableable is set to true, and which filter configuration rule that:
   * 
   * For example, for 
   * - the configuration rule own status is enable ;
   * - have a target ;
   * - the target is enable ;
   */
   private[this] def filterConfigurationRules(crs:Seq[ConfigurationRule]) : Box[Seq[ConfigurationRule]] = {
        val switchableCr: Seq[(ConfigurationRule,PolicyInstanceTarget)] = 
          //only cr with "own status == true" and completly defined (else their states can't change)
          crs.collect { case cr if(cr.isActivated) => (cr, cr.target.get) }

        //group by target, and check if target status is enable
        //if the target is disable, we can't change the cr status anyhow
        (sequence(switchableCr.groupBy { case (cr,t) => t }.toSeq) { case (target, seq) =>
          for {
            targetInfo <- targetService.getTargetInfo(target)
          } yield {
            if(targetInfo.isActivated) {
              seq.map { case(cr,_) => cr }
            } else {
              Seq()
            }
          }
        }).map( _.flatten )
    }
  
  
  /////////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// PolicyInstance dependencies //////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////////////
  
  /**
   * Find all Configuration rules that depend on that
   * policy instance.
   * For now, we don't care about dependencies yielded by parameterized values,
   * and so we just look for Configuration Rules with policyInstance=piId
   */
  override def policyInstanceDependencies(id:PolicyInstanceId, onlyForState:ModificationStatus = DontCare) : Box[PolicyInstanceDependencies] = {
    for {
      con <- ldap
      configRules <- searchConfigurationRules(con,id)
      filtered:Seq[ConfigurationRule] <- onlyForState match {
        case DontCare => Full(configRules)
        case OnlyEnableable => filterConfigurationRules(configRules)
        case OnlyDisableable => filterConfigurationRules(configRules)
      }
    } yield {
      PolicyInstanceDependencies(id,filtered)
    }
  }

  /**
   * Delete a given item and all its dependencies.
   * Return the list of items actually deleted.
   */
  override def cascadeDeletePolicyInstance(id:PolicyInstanceId, actor:EventActor) : Box[PolicyInstanceDependencies] = {
    for {
      con          <- ldap
      configRules  <- searchConfigurationRules(con,id)
      updatedRules <- sequence(configRules) { cr =>
                        //check that target is actually "target", and remove it
                        if(cr.policyInstanceIds.exists(i => id == i)) {
                          configurationRuleRepository.update(cr.copy(policyInstanceIds = cr.policyInstanceIds - id), actor) ?~!
                            "Can not update configuration rule with ID %s. %s".format(cr.id, {
                               val alreadyUpdated = configRules.takeWhile(x => x.id != cr.id)
                               if(alreadyUpdated.isEmpty) ""
                               else "Some rules were already updated: %s".format(alreadyUpdated.mkString(", "))
                            })
                        } else {
                          logger.debug("Do not remove policy instance with ID '%s' from configuration rule '%s' (already not present?)".format(id.value, cr.id.value))
                          None
                        }
      }
      diff         <- policyInstanceRepository.delete(id,actor) ?~! 
                      "Error when deleting policy instanc with ID %s. All dependent configuration rules where deleted %s.".format(
                          id, configRules.map( _.id.value ).mkString(" (", ", ", ")"))
    } yield {
      PolicyInstanceDependencies(id,configRules)
    }
  }
  
  /////////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// PolicyTemplate dependencies //////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////////////
  
  /**
   * Find all Configuration rules and policy isntances that depend on that
   * policy template.
   * If onlyEnableable is set to true, that method only return
   * dependent configuration rules which will switch from disabled to enabled 
   * if that policyTemplate was switching from disabled to enabled 
   * (independently from the actual status of that policy template).
   */
  def policyTemplateDependencies(id:UserPolicyTemplateId, onlyForState:ModificationStatus = DontCare) : Box[PolicyTemplateDependencies] = {
    for {
      con <- ldap
      pis <- policyInstanceRepository.getPolicyInstances(id)
      //if we are asked only for enable pis, remove disabled ones
      val filteredPis = onlyForState match {
        case DontCare => pis
        //if the policy template is not internally enable, there is no chance that its status will ever change
        case _ => pis.filter(pi => pi.isActivated)
      }
      piAndCrs <- sequence(filteredPis) { pi =>
        for {
          configRules <- searchConfigurationRules(con,pi.id)
          filtered:Seq[ConfigurationRule] <- onlyForState match {
            case DontCare => Full(configRules)
            case OnlyEnableable => filterConfigurationRules(configRules)
            case OnlyDisableable => filterConfigurationRules(configRules)
          }
        } yield {
          ( pi.id , (pi,filtered) )
        }
      }
    } yield {
      val allCrs = (for {
        (piId, (pi,seqCrs )) <- piAndCrs 
        cr <- seqCrs
      } yield {
        (cr.id, cr)
      }).toMap
      
      PolicyTemplateDependencies(
        id,
        piAndCrs.map { case ( (piId, (pi,seqCrs )) ) => (piId, (pi, seqCrs.map( _.id))) }.toMap, 
        allCrs
      )
    }
  }

  /**
   * Delete a given item and all its dependencies.
   * Return the list of items actually deleted.
   */
  def cascadeDeletePolicyTemplate(id:UserPolicyTemplateId, actor:EventActor) : Box[PolicyTemplateDependencies] = {
    for {
      con <- ldap
      pis <- policyInstanceRepository.getPolicyInstances(id)
      piMap = pis.map(pi => (pi.id, pi) ).toMap
      deletedPis <- sequence(pis) { pi =>
        cascadeDeletePolicyInstance(pi.id, actor) 
      }
      deletedUpt <- policyTemplateRepository.delete(id)
    } yield {
      val allCrs = scala.collection.mutable.Map[ConfigurationRuleId,ConfigurationRule]()
      val pis = deletedPis.map { case PolicyInstanceDependencies(policyInstanceId,seqCrs) =>
        allCrs ++= seqCrs.map( cr => (cr.id,cr))
        (policyInstanceId, (piMap(policyInstanceId),seqCrs.map( _.id)))
      }
      PolicyTemplateDependencies(id,pis.toMap,allCrs.toMap)
    }
    
  }
  
  
  /////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// Target dependencies //////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////
  
  private[this] def searchConfigurationRules(con:ReadOnlyLDAPConnection, target:PolicyInstanceTarget) : Box[Seq[ConfigurationRule]] = {
    sequence(con.searchOne(rudderDit.CONFIG_RULE.dn, EQ(A_POLICY_TARGET, target.target))) { entry =>
      mapper.entry2ConfigurationRule(entry)
    }  
  }

  /**
   * Find all Configuration rules that depend on that
   * target.
   * For now, we don't care about dependencies yielded by parameterized values,
   * and so we just look for Configuration Rules with targetname=target
   */
  override def targetDependencies(target:PolicyInstanceTarget, onlyEnableable:Boolean = false) : Box[TargetDependencies] = {
    /* utility method to call if only enableable is set to true, and which filter configuration rule that:
     * - the configuration rule own status is enable ;
     * - have a policy instance ;
     * - the policy instance is enable ;
     */
    def filterConfigurationRules(crs:Seq[ConfigurationRule]) : Box[Seq[ConfigurationRule]] = {
        val enabledCr: Seq[(ConfigurationRule,PolicyInstanceId)] = crs.collect { 
          case cr if(cr.isActivatedStatus && cr.policyInstanceIds.size > 0) => cr.policyInstanceIds.map(id => (cr, id)) 
        }.flatten
        //group by target, and check if target is enable
        (sequence(enabledCr.groupBy { case (cr,id) => id }.toSeq) { case (id, seq) =>
          for {
            pi <- policyInstanceRepository.getPolicyInstance(id)
            upt <- policyInstanceRepository.getUserPolicyTemplate(id)
          } yield {
            if(pi.isActivated && upt.isActivated) {
              seq.map { case(id,_) => id }
            } else {
              Seq()
            }
          }
        }).map( _.flatten )
    }
    
    for {
      con <- ldap
      configRules <- searchConfigurationRules(con,target)
      filtered:Seq[ConfigurationRule] <- if(onlyEnableable) filterConfigurationRules(configRules) else Full(configRules)
    } yield {
      TargetDependencies(target,filtered)
    }
  }

  /**
   * Delete a given item and all its dependencies.
   * Return the list of items actually deleted.
   */
  override def cascadeDeleteTarget(target:PolicyInstanceTarget, actor:EventActor) : Box[TargetDependencies] = {
    target match {
      case GroupTarget(groupId) =>
        for {
          con           <- ldap
          configRules   <- searchConfigurationRules(con,target)
          updatedRules  <- sequence(configRules) { cr =>
                             //check that target is actually "target", and remove it
                             cr.target match {
                               case Some(t) if(t == target) => 
                                 configurationRuleRepository.update(cr.copy(target = None), actor) ?~! 
                                   "Can not remove target '%s' from configuration rule with Dd '%s'. %s".format(
                                       target.target, cr.id.value, {
                                         val alreadyUpdated = configRules.takeWhile(x => x.id != cr.id)
                                         if(alreadyUpdated.isEmpty) ""
                                         else "Some rules were already updated: %s".format(alreadyUpdated.mkString(", "))
                                       }
                                   )
                               case x => 
                                 logger.debug("Do not cascade modify configuration rule with ID '%s', because its target is '%s' and we are deleting '%s'".
                                     format(cr.id.value, cr.target.map( _.target), target.target))
                                 Full(None)
                             }
                           }
          deletedTarget <- groupRepository.delete(groupId, actor) ?~!
                            "Error when deleting target %s. All dependent configuration rules where updated %s".format(
                              target, configRules.map( _.id.value ).mkString("(", ", ", ")" ))
        } yield {
          TargetDependencies(target,configRules)
        }
        
      case _ => Failure("Can not delete the special target: %s ; abort".format(target))
    }
  }    
  
}
