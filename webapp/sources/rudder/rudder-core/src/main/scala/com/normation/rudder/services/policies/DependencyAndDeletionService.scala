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

package com.normation.rudder.services.policies

import com.normation.rudder.domain.policies.{CompositeRuleTarget, GroupTarget, RuleTarget}
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.Rule
import net.liftweb.common._
import com.normation.rudder.domain.policies.{ActiveTechniqueId, DirectiveId, RuleId}
import com.normation.rudder.domain.{RudderDit, RudderLDAPConstants}
import RudderLDAPConstants._
import com.normation.utils.Control.sequence
import com.normation.ldap.sdk.{BuildFilter, LDAPConnectionProvider}
import BuildFilter._
import com.normation.rudder.repository._
import com.normation.eventlog.EventActor
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.ModificationId
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.LdapResult._
import cats.implicits._
import com.normation.NamedZioLogger
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.errors._
import com.normation.box._

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
  def directiveDependencies(id:DirectiveId, groupLib: Box[FullNodeGroupCategory], onlyForState:ModificationStatus = DontCare) : Box[DirectiveDependencies]

  /**
   * Delete a given item and modify all objects that depends on it.
   * The actual action on objects depend of their use of the item, and can
   * be: delete item, make the item no more use that directive, etc.
   * Return the list of items actually modified.
   */
  def cascadeDeleteDirective(id:DirectiveId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DirectiveDependencies]

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
  def techniqueDependencies(id:ActiveTechniqueId, groupLib: Box[FullNodeGroupCategory], onlyForState:ModificationStatus = DontCare) : Box[TechniqueDependencies]

  /**
   * Delete a given item and modify all objects that depends on it.
   * The actual action on objects depend of their use of the item, and can
   * be: delete item, make the item no more use that directive, etc.
   * Return the list of items actually modified.
   */
  def cascadeDeleteTechnique(id:ActiveTechniqueId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[TechniqueDependencies]

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
  def cascadeDeleteTarget(target:RuleTarget, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[TargetDependencies]


}



///////////////////////////////// default implementation /////////////////////////////////

class DependencyAndDeletionServiceImpl(
    ldap                 : LDAPConnectionProvider[RoLDAPConnection]
  , rudderDit            : RudderDit
  , roDirectiveRepository: RoDirectiveRepository
  , woDirectiveRepository: WoDirectiveRepository
  , woRuleRepository     : WoRuleRepository
  , woGroupRepository    : WoNodeGroupRepository
  , mapper               : LDAPEntityMapper
) extends DependencyAndDeletionService with NamedZioLogger {

  override def loggerName: String = this.getClass.getName

  /**
   * Utility method that find rules which depends upon a directive.
   * Some rules may be omited
   */
  private[this] def searchRules(
      con:RoLDAPConnection
    , id:DirectiveId
  ): IOResult[Seq[Rule]] = {
    for {
      entries <- con.searchOne(rudderDit.RULES.dn, EQ(A_DIRECTIVE_UUID, id.value))
      res     <- ZIO.foreach(entries) { entry =>
                   mapper.entry2Rule(entry).toIO
                 }
    } yield {
      res
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
  private[this] def filterRules(rules:Seq[Rule], groupLib: FullNodeGroupCategory) : IOResult[Seq[Rule]] = {
    val switchableCr: Seq[(Rule, Set[RuleTarget])] =
      // only rule with "own status == true" and completly defined
      // (else their states can't change)
      rules.collect {
        case rule if(rule.isEnabled) => (rule, rule.targets)
      }

    // group by target, and check if target status is enable
    // if the target is disable, we can't change the rule status anyhow
    ZIO.foreach(switchableCr) { case (rule, targets) =>
      ZIO.foreach(targets) { target =>
        for {
          targetInfo <- groupLib.allTargets.get(target).notOptional("target info must be defined")
          _          <- if(targetInfo.isEnabled) UIO.unit
                        else Unconsistancy(s"target is not enable: ${targetInfo.name}").fail
        } yield {
          rule
        }
      }
    }.map( _.flatten )
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
  override def directiveDependencies(id:DirectiveId, boxGroupLib: Box[FullNodeGroupCategory], onlyForState:ModificationStatus = DontCare) : Box[DirectiveDependencies] = {
    for {
      con         <- ldap
      configRules <- searchRules(con,id)
      groupLib    <- boxGroupLib.toIO
      filtered    <- onlyForState match {
                       case DontCare        => configRules.succeed
                       case OnlyEnableable  => filterRules(configRules, groupLib)
                       case OnlyDisableable => filterRules(configRules, groupLib)
                     }
    } yield {
      DirectiveDependencies(id,filtered.toSet)
    }
  }.toBox

  /**
   * Delete a given item and all its dependencies.
   * Return the list of items actually deleted.
   */
  override def cascadeDeleteDirective(id:DirectiveId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DirectiveDependencies] = {
    for {
      con          <- ldap
      configRules  <- searchRules(con,id)
      diff         <- woDirectiveRepository.delete(id, modId, actor, reason).chainError(s"Error when deleting policy instanc with ID '${id.value}'.")
      updatedRules <- ZIO.foreach(configRules) { rule =>
                        //check that directive is actually in rule directives, and remove it
                        if(rule.directiveIds.exists(i => id == i)) {
                          val newRule = rule.copy(directiveIds = rule.directiveIds - id)
                          val updatedRuleRes = if(rule.isSystem) {
                            woRuleRepository.updateSystem(newRule, modId, actor, reason)
                          } else {
                            woRuleRepository.update(newRule, modId, actor, reason)
                          }
                          updatedRuleRes.chainError(
                            s"Can not remove directive '${id.value}' from rule with ID '${rule.id.value}'. %s".format {
                               val alreadyUpdated = configRules.takeWhile(x => x.id != rule.id)
                               if(alreadyUpdated.isEmpty) ""
                               else "Some rules were already updated: %s".format(alreadyUpdated.mkString(", "))
                          })
                        } else {
                          logPure.debug(s"Do not remove directive with ID '${id.value}' from rule '${rule.id.value}' (already not present?)") *>
                          None.succeed
                        }
      }
    } yield {
      DirectiveDependencies(id,configRules.toSet)
    }
  }.toBox

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
  def techniqueDependencies(id:ActiveTechniqueId, boxGroupLib: Box[FullNodeGroupCategory], onlyForState:ModificationStatus = DontCare) : Box[TechniqueDependencies] = {
    for {
      con         <- ldap
      directives  <- roDirectiveRepository.getDirectives(id)
      //if we are asked only for enable directives, remove disabled ones
      filteredPis =  onlyForState match {
                       case DontCare => directives
                       //if the technique is not internally enable, there is no chance that its status will ever change
                       case _ => directives.filter(directive => directive.isEnabled)
                     }
      groupLib    <- boxGroupLib.toIO
      piAndCrs    <- ZIO.foreach(filteredPis) { directive =>
                      for {
                        configRules  <- searchRules(con,directive.id)
                        filtered     <- onlyForState match {
                                          case DontCare        => configRules.succeed
                                          case OnlyEnableable  => filterRules(configRules, groupLib)
                                          case OnlyDisableable => filterRules(configRules, groupLib)
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
  }.toBox

  /**
   * Delete a given item and all its dependencies.
   * Return the list of items actually deleted.
   */
  def cascadeDeleteTechnique(id:ActiveTechniqueId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[TechniqueDependencies] = {
    for {
      con        <- ldap
      directives <- roDirectiveRepository.getDirectives(id)
      piMap      =  directives.map(directive => (directive.id, directive) ).toMap
      deletedPis <- ZIO.foreach(directives) { directive =>
                      cascadeDeleteDirective(directive.id, modId, actor, reason = reason).toIO
                    }
      deletedActiveTechnique <- woDirectiveRepository.delete(id, modId, actor, reason)
    } yield {
      val allCrs = scala.collection.mutable.Map[RuleId,Rule]()
      val directives = deletedPis.map { case DirectiveDependencies(directiveId,seqCrs) =>
        allCrs ++= seqCrs.map( rule => (rule.id,rule))
        (directiveId, (piMap(directiveId),seqCrs.map( _.id)))
      }
      TechniqueDependencies(id,directives.toMap,allCrs.toMap)
    }

  }.toBox


  /////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// Target dependencies //////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////

  private[this] def searchRules(con:RoLDAPConnection, target:RuleTarget) : IOResult[Seq[Rule]] = {
    for {
      entries <- con.searchOne(rudderDit.RULES.dn, SUB(A_RULE_TARGET,null,Array(target.target),null))
      res     <- ZIO.foreach(entries) { entry =>
                   mapper.entry2Rule(entry).toIO
                 }
    } yield {
      res
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
    def filterRules(rules:Seq[Rule]) : IOResult[Seq[Rule]] = {
        val enabledCr: Seq[(Rule,DirectiveId)] = rules.collect {
          case rule if(rule.isEnabledStatus && rule.directiveIds.size > 0) => rule.directiveIds.map(id => (rule, id))
        }.flatten
        //group by target, and check if target is enable
        ZIO.foreach(enabledCr.groupBy { case (rule,id) => id }) { case (id, seq) =>
          for {
            res <- roDirectiveRepository.getActiveTechniqueAndDirective(id).chainError(s"Error when retrieving directive with ID ${id.value}''")
          } yield {
            val (activeTechnique, directive) = res
            if(directive.isEnabled && activeTechnique.isEnabled) {
              seq.map { case(id,_) => id }
            } else {
              Seq()
            }
          }
        }.map( _.flatten )
    }

    for {
      con          <- ldap
      configRules  <- searchRules(con,target)
      filtered     <- if(onlyEnableable) filterRules(configRules) else configRules.succeed
    } yield {
      TargetDependencies(target,filtered.toSet)
    }
  }.toBox

  /**
   * Delete a given item and all its dependencies.
   * Return the list of items actually deleted.
   */
  override def cascadeDeleteTarget(targetToDelete:RuleTarget, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[TargetDependencies] = {
    // Update Rule to remove the target
    def updateRule ( rule : Rule ) = {
      // Target directly removed
      val removedTargets = rule.targets - targetToDelete
      // Remove target from composite targets
      val updatedTargets = removedTargets.map({
        case composite : CompositeRuleTarget => composite.removeTarget(targetToDelete)
        case t => t
      } )
      // Update the Rule and save it
      val updatedRule = rule.copy(targets = updatedTargets)
      val updatedRuleRes = if(rule.isSystem) {
        woRuleRepository.updateSystem(updatedRule, modId, actor, reason)
      } else {
        woRuleRepository.update(updatedRule, modId, actor, reason)
      }
      updatedRuleRes.chainError(s"Can not remove target '${targetToDelete.target}' from rule with id '${rule.id.value}'.")
    }

    targetToDelete match {
      case GroupTarget(groupId) =>
        for {
          con           <- ldap
          configRules   <- searchRules(con,targetToDelete)
          updatedRules  <- ZIO.foreach(configRules)(updateRule)
          deletedTarget <- woGroupRepository.delete(groupId, modId, actor, reason).chainError(
                            "Error when deleting target %s. All dependent rules where updated %s".format(
                              targetToDelete, configRules.map( _.id.value ).mkString("(", ", ", ")" ))
                           )
        } yield {
          TargetDependencies(targetToDelete,configRules.toSet)
        }

      case _ => "Can not delete the special target: %s ; abort".format(targetToDelete).fail
    }
  }.toBox

}
