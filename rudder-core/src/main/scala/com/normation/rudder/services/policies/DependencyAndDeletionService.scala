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
  GroupTarget,RuleTarget
}
import com.unboundid.ldap.sdk.DN
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.Rule
import net.liftweb.common._
import com.normation.rudder.domain.policies.{ActiveTechniqueId, RuleId, DirectiveId}
import com.normation.rudder.domain.{RudderLDAPConstants, RudderDit}
import RudderLDAPConstants._
import com.normation.utils.Control.sequence
import com.normation.ldap.sdk.{ReadOnlyLDAPConnection, BuildFilter, LDAPConnectionProvider}
import BuildFilter._
import com.normation.rudder.repository._
import com.normation.eventlog.EventActor
import com.normation.utils.HashcodeCaching


/**
 * A container for items which depend on directives
 */
case class DirectiveDependencies(
  directiveId:DirectiveId,
  rules:Set[Rule]
) extends HashcodeCaching 

/**
 * A container for items which depend on directives
 */
case class TargetDependencies(
  target:RuleTarget,
  rules:Set[Rule]
) extends HashcodeCaching 

/**
 * A container for items which depend on technique
 * For now, we don't care of directive <-> rules
 */
case class TechniqueDependencies(
  activeTechniqueId:ActiveTechniqueId,
  directives:Map[DirectiveId, (Directive,Set[RuleId])],
  rules:Map[RuleId,Rule]
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
 * all rules which have that group as target.
 */
trait DependencyAndDeletionService {

  /**
   * Find all rules that depend on that
   * directive.
   * onlyForState allows to filter dependencies based on the new status
   * they should have if the parent become of a given status.
   * For example, if <code>onlyForState</code> is set to OnlyEnableable,
   * that method only return dependent items which will switch from disabled to enabled 
   * if that directive was switching from disabled to enabled 
   * (independently from the actual status of that directive).
   * The DontCare ModificationStatus does not filter. 
   */
  def directiveDependencies(id:DirectiveId, onlyForState:ModificationStatus = DontCare) : Box[DirectiveDependencies]

  /**
   * Delete a given item and modify all objects that depends on it.
   * The actual action on objects depend of their use of the item, and can 
   * be: delete item, make the item no more use that directive, etc. 
   * Return the list of items actually modified.
   */
  def cascadeDeleteDirective(id:DirectiveId, actor:EventActor, reason:Option[String]) : Box[DirectiveDependencies]

  /**
   * Find all rules and directives that depend on that
   * technique.
   * onlyForState allows to filter dependencies based on the new status
   * they should have if the parent become of a given status.
   * For example, if <code>onlyForState</code> is set to OnlyEnableable,
   * that method only return dependent items which will switch from disabled to enabled 
   * if that technique was switching from disabled to enabled 
   * (independently from the actual status of that technique).
   * The DontCare ModificationStatus does not filter. 
   */
  def techniqueDependencies(id:ActiveTechniqueId, onlyForState:ModificationStatus = DontCare) : Box[TechniqueDependencies]

  /**
   * Delete a given item and modify all objects that depends on it.
   * The actual action on objects depend of their use of the item, and can 
   * be: delete item, make the item no more use that directive, etc. 
   * Return the list of items actually modified.
   */
  def cascadeDeleteTechnique(id:ActiveTechniqueId, actor:EventActor, reason:Option[String]) : Box[TechniqueDependencies]

  /**
   * Find all rules that depend on that
   * ptarget.
   * If onlyEnableable is set to true, that method only return
   * dependent item which will switch from disabled to enabled 
   * if that target was switching from disabled to enabled 
   * (independently from the actual status of that target).
   */
  def targetDependencies(target:RuleTarget, onlyEnableable:Boolean = true) : Box[TargetDependencies]

  /**
   * Delete a given item and modify all objects that depends on it.
   * The actual action on objects depend of their use of the item, and can 
   * be: delete item, make the item no more use that directive, etc. 
   * Return the list of items actually modified.
   */
  def cascadeDeleteTarget(target:RuleTarget, actor:EventActor, reason:Option[String]) : Box[TargetDependencies]
  
  
}



///////////////////////////////// default implementation /////////////////////////////////

class DependencyAndDeletionServiceImpl(
    ldap                       : LDAPConnectionProvider
  , rudderDit                  : RudderDit
  , directiveRepository   : DirectiveRepository
  , techniqueRepository   : ActiveTechniqueRepository
  , ruleRepository: RuleRepository
  , groupRepository            : NodeGroupRepository
  , mapper                     : LDAPEntityMapper
  , targetService              : RuleTargetService
) extends DependencyAndDeletionService with Loggable {

  /**
   * Utility method that find rules which depends upon a directive. 
   * Some rules may be omited
   */
  private[this] def searchRules(
      con:ReadOnlyLDAPConnection
    , id:DirectiveId
  ):Box[Seq[Rule]] = {
    sequence(con.searchOne(rudderDit.RULES.dn, EQ(A_DIRECTIVE_UUID, id.value))) { entry =>
      mapper.entry2Rule(entry)
    }
  }
  

  /**
   * utility method to call if only enableable is set to true, and which filter rule that:
   * 
   * For example, for 
   * - the rule own status is enable ;
   * - have a target ;
   * - the target is enable ;
   */
   private[this] def filterRules(rules:Seq[Rule]) : Box[Seq[Rule]] = {
        val switchableCr: Seq[(Rule, Set[RuleTarget])] = 
          // only rule with "own status == true" and completly defined 
          // (else their states can't change)
          rules.collect { 
            case rule if(rule.isEnabled) => (rule, rule.targets) 
          }

        // group by target, and check if target status is enable
        // if the target is disable, we can't change the rule status anyhow
        (sequence(switchableCr) { case (rule, targets) =>
          sequence(targets.toSeq) { target =>
            for {
              targetInfo <- targetService.getTargetInfo(target)
              if targetInfo.isEnabled
            } yield {
              rule
            }
          }
        }).map( _.flatten )
    }
  
  
  /////////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// Directive dependencies //////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////////////
  
  /**
   * Find all rules that depend on that
   * directive.
   * For now, we don't care about dependencies yielded by parameterized values,
   * and so we just look for rules with directive=directiveId
   */
  override def directiveDependencies(id:DirectiveId, onlyForState:ModificationStatus = DontCare) : Box[DirectiveDependencies] = {
    for {
      con <- ldap
      configRules <- searchRules(con,id)
      filtered:Seq[Rule] <- onlyForState match {
        case DontCare => Full(configRules)
        case OnlyEnableable => filterRules(configRules)
        case OnlyDisableable => filterRules(configRules)
      }
    } yield {
      DirectiveDependencies(id,filtered.toSet)
    }
  }

  /**
   * Delete a given item and all its dependencies.
   * Return the list of items actually deleted.
   */
  override def cascadeDeleteDirective(id:DirectiveId, actor:EventActor, reason:Option[String]) : Box[DirectiveDependencies] = {
    for {
      con          <- ldap
      configRules  <- searchRules(con,id)
      updatedRules <- sequence(configRules) { rule =>
                        //check that target is actually "target", and remove it
                        if(rule.directiveIds.exists(i => id == i)) {
                          ruleRepository.update(rule.copy(directiveIds = rule.directiveIds - id), actor, reason) ?~!
                            "Can not update rule with ID %s. %s".format(rule.id, {
                               val alreadyUpdated = configRules.takeWhile(x => x.id != rule.id)
                               if(alreadyUpdated.isEmpty) ""
                               else "Some rules were already updated: %s".format(alreadyUpdated.mkString(", "))
                            })
                        } else {
                          logger.debug("Do not remove directive with ID '%s' from rule '%s' (already not present?)".format(id.value, rule.id.value))
                          None
                        }
      }
      diff         <- directiveRepository.delete(id,actor, reason) ?~! 
                      "Error when deleting policy instanc with ID %s. All dependent rules where deleted %s.".format(
                          id, configRules.map( _.id.value ).mkString(" (", ", ", ")"))
    } yield {
      DirectiveDependencies(id,configRules.toSet)
    }
  }
  
  /////////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// Technique dependencies //////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////////////
  
  /**
   * Find all rules and directives that depend on that
   * technique.
   * If onlyEnableable is set to true, that method only return
   * dependent rules which will switch from disabled to enabled 
   * if that technique was switching from disabled to enabled 
   * (independently from the actual status of that technique).
   */
  def techniqueDependencies(id:ActiveTechniqueId, onlyForState:ModificationStatus = DontCare) : Box[TechniqueDependencies] = {
    for {
      con <- ldap
      directives <- directiveRepository.getDirectives(id)
      //if we are asked only for enable directives, remove disabled ones
      val filteredPis = onlyForState match {
        case DontCare => directives
        //if the technique is not internally enable, there is no chance that its status will ever change
        case _ => directives.filter(directive => directive.isEnabled)
      }
      piAndCrs <- sequence(filteredPis) { directive =>
        for {
          configRules <- searchRules(con,directive.id)
          filtered:Seq[Rule] <- onlyForState match {
            case DontCare => Full(configRules)
            case OnlyEnableable => filterRules(configRules)
            case OnlyDisableable => filterRules(configRules)
          }
        } yield {
          ( directive.id , (directive,filtered) )
        }
      }
    } yield {
      val allCrs = (for {
        (directiveId, (directive,seqCrs )) <- piAndCrs 
        rule <- seqCrs
      } yield {
        (rule.id, rule)
      }).toMap
      
      TechniqueDependencies(
        id,
        piAndCrs.map { case ( (directiveId, (directive,seqCrs )) ) => (directiveId, (directive, seqCrs.map( _.id).toSet )) }.toMap,
        allCrs
      )
    }
  }

  /**
   * Delete a given item and all its dependencies.
   * Return the list of items actually deleted.
   */
  def cascadeDeleteTechnique(id:ActiveTechniqueId, actor:EventActor, reason:Option[String]) : Box[TechniqueDependencies] = {
    for {
      con <- ldap
      directives <- directiveRepository.getDirectives(id)
      piMap = directives.map(directive => (directive.id, directive) ).toMap
      deletedPis <- sequence(directives) { directive =>
        cascadeDeleteDirective(directive.id, actor, reason = reason) 
      }
      deletedActiveTechnique <- techniqueRepository.delete(id, actor, reason)
    } yield {
      val allCrs = scala.collection.mutable.Map[RuleId,Rule]()
      val directives = deletedPis.map { case DirectiveDependencies(directiveId,seqCrs) =>
        allCrs ++= seqCrs.map( rule => (rule.id,rule))
        (directiveId, (piMap(directiveId),seqCrs.map( _.id)))
      }
      TechniqueDependencies(id,directives.toMap,allCrs.toMap)
    }
    
  }
  
  
  /////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// Target dependencies //////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////
  
  private[this] def searchRules(con:ReadOnlyLDAPConnection, target:RuleTarget) : Box[Seq[Rule]] = {
    sequence(con.searchOne(rudderDit.RULES.dn, EQ(A_RULE_TARGET, target.target))) { entry =>
      mapper.entry2Rule(entry)
    }  
  }

  /**
   * Find all rules that depend on that
   * target.
   * For now, we don't care about dependencies yielded by parameterized values,
   * and so we just look for rules with targetname=target
   */
  override def targetDependencies(target:RuleTarget, onlyEnableable:Boolean = false) : Box[TargetDependencies] = {
    /* utility method to call if only enableable is set to true, and which filter rule that:
     * - the rule own status is enable ;
     * - have a directive ;
     * - the directive is enable ;
     */
    def filterRules(rules:Seq[Rule]) : Box[Seq[Rule]] = {
        val enabledCr: Seq[(Rule,DirectiveId)] = rules.collect { 
          case rule if(rule.isEnabledStatus && rule.directiveIds.size > 0) => rule.directiveIds.map(id => (rule, id)) 
        }.flatten
        //group by target, and check if target is enable
        (sequence(enabledCr.groupBy { case (rule,id) => id }.toSeq) { case (id, seq) =>
          for {
            directive <- directiveRepository.getDirective(id)
            activeTechnique <- directiveRepository.getActiveTechnique(id)
          } yield {
            if(directive.isEnabled && activeTechnique.isEnabled) {
              seq.map { case(id,_) => id }
            } else {
              Seq()
            }
          }
        }).map( _.flatten )
    }
    
    for {
      con <- ldap
      configRules <- searchRules(con,target)
      filtered:Seq[Rule] <- if(onlyEnableable) filterRules(configRules) else Full(configRules)
    } yield {
      TargetDependencies(target,filtered.toSet)
    }
  }

  /**
   * Delete a given item and all its dependencies.
   * Return the list of items actually deleted.
   */
  override def cascadeDeleteTarget(target:RuleTarget, actor:EventActor, reason:Option[String]) : Box[TargetDependencies] = {
    target match {
      case GroupTarget(groupId) =>
        for {
          con           <- ldap
          configRules   <- searchRules(con,target)
          updatedRules  <- sequence(configRules) { rule =>
                             //check that target is actually "target", and remove it
                             if (rule.targets.contains(target)) {
                                 ruleRepository.update(rule.copy(targets = rule.targets - target), actor, reason) ?~! 
                                   "Can not remove target '%s' from rule with Dd '%s'. %s".format(
                                       target.target, rule.id.value, {
                                         val alreadyUpdated = configRules.takeWhile(x => x.id != rule.id)
                                         if(alreadyUpdated.isEmpty) ""
                                         else "Some rules were already updated: %s".format(alreadyUpdated.mkString(", "))
                                       }
                                   )
                             } else {
                                logger.debug("Do not cascade modify rule with ID '%s', because its target is '%s' and we are deleting '%s'".
                                    format(rule.id.value, rule.targets.map( _.target), target.target))
                                Full(None)
                             }
                           }
          deletedTarget <- groupRepository.delete(groupId, actor, reason) ?~!
                            "Error when deleting target %s. All dependent rules where updated %s".format(
                              target, configRules.map( _.id.value ).mkString("(", ", ", ")" ))
        } yield {
          TargetDependencies(target,configRules.toSet)
        }
        
      case _ => Failure("Can not delete the special target: %s ; abort".format(target))
    }
  }    
  
}
