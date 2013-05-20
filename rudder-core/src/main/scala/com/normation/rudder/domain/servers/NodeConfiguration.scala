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

package com.normation.rudder.domain.servers



import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.cfclerk.domain.Variable
import net.liftweb.common._
import org.joda.time.DateTime
import com.normation.cfclerk.domain.Cf3PolicyDraft
import com.normation.cfclerk.domain.{TechniqueId, Cf3PolicyDraftId,Cf3PolicyDraft, Cf3PolicyDraftContainer}
import com.normation.rudder.domain.policies.{Rule,RuleId}
import com.normation.utils.HashcodeCaching
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId

case class MinimalNodeConfig(
    name                         : String
  , hostname                     : String
  , agentsName                   : Seq[AgentType]
  , policyServerId               : String
  , localAdministratorAccountName: String
) extends HashcodeCaching

sealed trait NodeConfiguration extends Loggable {
  def id                        : NodeId
  def currentRulePolicyDrafts   : Seq[RuleWithCf3PolicyDraft]
  def targetRulePolicyDrafts    : Seq[RuleWithCf3PolicyDraft]
  def isPolicyServer            : Boolean
  def currentMinimalNodeConfig  : MinimalNodeConfig
  def targetMinimalNodeConfig   : MinimalNodeConfig
  def writtenDate               : Option[DateTime]
  def currentSystemVariables    : Map[String, Variable]
  def targetSystemVariables     : Map[String, Variable]

  /**
   * Add a directive
   * @param policy
   * @return
   */
  def addDirective(ruleWithCf3PolicyDraft : RuleWithCf3PolicyDraft) : Box[NodeConfiguration]= {
    targetRulePolicyDrafts.find( _.draftId == ruleWithCf3PolicyDraft.draftId) match {
      case None =>
        val map =  targetRulePolicyDrafts.map { rcf3 =>
          ( rcf3.draftId -> rcf3)
        }.toMap



        val newRulePolicyDrafts = (updateAllUniqueVariables(ruleWithCf3PolicyDraft.cf3PolicyDraft,map)
            + (ruleWithCf3PolicyDraft.draftId -> ruleWithCf3PolicyDraft.copy()))

            Full(copySetTargetRulePolicyDrafts(newRulePolicyDrafts.values.toSeq))
      case Some(x) => Failure("An instance of the ruleWithCf3PolicyDraft with the same identifier %s already exists".format(ruleWithCf3PolicyDraft.draftId.value))
    }
  }

  def setSerial(rules : Seq[(RuleId,Int)]) : NodeConfiguration = {
    val newRulePolicyDrafts =  collection.mutable.Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft]()
        targetRulePolicyDrafts.foreach { ruleWithCf3PolicyDraft =>
            newRulePolicyDrafts += ( ruleWithCf3PolicyDraft.draftId ->ruleWithCf3PolicyDraft.copy()) }


    for ((id,serial) <- rules) {
      newRulePolicyDrafts ++= newRulePolicyDrafts.
            filter(x => x._2.ruleId == id).
            map(x => (x._1 -> new RuleWithCf3PolicyDraft(x._2.ruleId, x._2.cf3PolicyDraft.copy(serial = serial))))
    }
    copySetTargetRulePolicyDrafts(newRulePolicyDrafts.values.toSeq)
  }

  def copySetTargetRulePolicyDrafts(policies: Seq[RuleWithCf3PolicyDraft]): NodeConfiguration

  /**
   * Called when we have written the promises, to say that the current configuration is indeed the target one
   * @return the commmited NodeConfiguration
   */
  def commitModification() : NodeConfiguration

   /**
   * Called when we want to rollback modification, to say that the target configuration is the current one
   * @return the rolled back NodeConfiguration
   */
  def rollbackModification() : NodeConfiguration


  /**
   * Check if the node is modified and must be written
   * Modified is :
   * * Not the same number of policies
   * * Not the same ID of policies
   * * Serial different between two policies
   */
  def isModified : Boolean = {
    if(currentRulePolicyDrafts.size != targetRulePolicyDrafts.size) true
    else if(currentRulePolicyDrafts.map( _.draftId).toSet != targetRulePolicyDrafts.map( _.draftId ).toSet ) true
    else {
      for {
        currentCFC <- currentRulePolicyDrafts
        key        =  currentCFC.draftId
      } {
        val targetPi = targetRulePolicyDrafts.find( _.draftId == key).getOrElse(return true)
        if(currentCFC.cf3PolicyDraft.serial != targetPi.cf3PolicyDraft.serial) return true
      }
      false
    }

  }

  /**
   * Go through each Cf3PolicyDraft and update the unique variable
   * it has a side effect on policies
   * @param policy
   */
  protected def updateAllUniqueVariables(
      policy : Cf3PolicyDraft
    , ruleWithPolicies : Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft]
  ) : Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft] = {
    val policies = ruleWithPolicies.map { case(id, rp) => rp.cf3PolicyDraft }.toSeq
    val newPolicies = policy.updateAllUniqueVariables(policies).map(x => (x.id,x)).toMap
    val newMap = ruleWithPolicies.map { case (id, rp) =>
      ( id, newPolicies.get(id).map(x => rp.copy(cf3PolicyDraft = x)).getOrElse(rp) )
    }

    newMap
  }


  def findDirectiveByTechnique(techniqueId : TechniqueId): Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft] = {
    targetRulePolicyDrafts.filter(x =>
      x.cf3PolicyDraft.technique.id.name.value.equalsIgnoreCase(techniqueId.name.value) &&
      x.cf3PolicyDraft.technique.id.version == techniqueId.version
    ).map(x => (x.draftId , x)).toMap
  }

  def findCurrentDirectiveByTechnique(techniqueId : TechniqueId) = {
    currentRulePolicyDrafts.filter { x =>
      x.cf3PolicyDraft.technique.id.name.value.equalsIgnoreCase(techniqueId.name.value) &&
      x.cf3PolicyDraft.technique.id.version == techniqueId.version
    }.map(x => (x.draftId, x))
  }

  def getAllPoliciesNames(): Set[TechniqueId] = {
    targetRulePolicyDrafts.map( _.cf3PolicyDraft.technique.id).toSet
  }
}



object NodeConfiguration {
  def toContainer(outPath : String, server : NodeConfiguration) : Cf3PolicyDraftContainer = {
    val container = new Cf3PolicyDraftContainer(outPath)
    server.targetRulePolicyDrafts foreach (x =>  container.add(x.cf3PolicyDraft))
    container
  }
}



///////////////////////////////////////
//////// Implementations //////////////
///////////////////////////////////////

final case class RootNodeConfiguration(
    id                       : NodeId
  , currentRulePolicyDrafts  : Seq[RuleWithCf3PolicyDraft] = Seq()
  , targetRulePolicyDrafts   : Seq[RuleWithCf3PolicyDraft] = Seq()
  , isPolicyServer           : Boolean
  , currentMinimalNodeConfig : MinimalNodeConfig
  , targetMinimalNodeConfig  : MinimalNodeConfig
  , writtenDate              : Option[DateTime]
  , currentSystemVariables   : Map[String, Variable]
  , targetSystemVariables    : Map[String, Variable]
) extends NodeConfiguration with HashcodeCaching {

  def copySetTargetRulePolicyDrafts(policies: Seq[RuleWithCf3PolicyDraft]): RootNodeConfiguration = {
    this.copy(targetRulePolicyDrafts = policies)
  }

  /**
   * Called when we have written the promises, to say that the current configuration is indeed the target one
   * @return the updated configuration with "current" updated to match "target"
   */
  def commitModification() : RootNodeConfiguration = {
    logger.debug("Commiting node configuration " + this);

    this.copy(currentRulePolicyDrafts = this.targetRulePolicyDrafts,
        currentMinimalNodeConfig = this.targetMinimalNodeConfig,
        currentSystemVariables = this.targetSystemVariables,
        writtenDate = Some(DateTime.now())
    )

  }

  /**
   * Called when we want to rollback modification, to say that the target configuration is the current one
   * @return the updated configuration with "target" updated to match "current"
   */
  def rollbackModification() : RootNodeConfiguration = {
    copy(targetRulePolicyDrafts = this.currentRulePolicyDrafts,
        targetMinimalNodeConfig = this.currentMinimalNodeConfig,
        targetSystemVariables = this.currentSystemVariables
        )

  }
}

object RootNodeConfiguration {
  def apply(server:NodeConfiguration) : RootNodeConfiguration = {
    val root = new RootNodeConfiguration(server.id,
        server.currentRulePolicyDrafts,
        server.targetRulePolicyDrafts,
        server.isPolicyServer,
        server.currentMinimalNodeConfig,
        server.targetMinimalNodeConfig,
        server.writtenDate,
        server.currentSystemVariables,
        server.targetSystemVariables)

    root
  }
}

final case class SimpleNodeConfiguration(
    id                       : NodeId
  , currentRulePolicyDrafts  : Seq[RuleWithCf3PolicyDraft] = Seq()
  , targetRulePolicyDrafts   : Seq[RuleWithCf3PolicyDraft] = Seq()
  , isPolicyServer           : Boolean = false
  , currentMinimalNodeConfig : MinimalNodeConfig
  , targetMinimalNodeConfig  : MinimalNodeConfig
  , writtenDate              : Option[DateTime]
  , currentSystemVariables   : Map[String, Variable]
  , targetSystemVariables    : Map[String, Variable]
) extends NodeConfiguration with HashcodeCaching {


  def copySetTargetRulePolicyDrafts(policies: Seq[RuleWithCf3PolicyDraft]): SimpleNodeConfiguration = {
    this.copy(targetRulePolicyDrafts = policies)
  }

  /**
   * Called when we have written the promises, to say that the current configuration is indeed the target one
   * @return nothing
   */
  def commitModification() : SimpleNodeConfiguration = {
    logger.debug("Commiting server " + this);

    copy(currentRulePolicyDrafts = this.targetRulePolicyDrafts,
        currentMinimalNodeConfig = this.targetMinimalNodeConfig,
        currentSystemVariables = this.targetSystemVariables,
        writtenDate = Some(DateTime.now())
     )

  }

  /**
   * Called when we want to rollback modification, to say that the target configuration is the current one
   * @return nothing
   */
  def rollbackModification() : SimpleNodeConfiguration = {
    copy(targetRulePolicyDrafts = this.currentRulePolicyDrafts,
        targetMinimalNodeConfig = this.currentMinimalNodeConfig,
        targetSystemVariables = this.currentSystemVariables
        )

  }

  override def toString() = "%s %s".format(currentMinimalNodeConfig.name, id)

}

