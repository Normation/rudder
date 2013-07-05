/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

import net.liftweb.common._
import com.normation.rudder.domain.parameters._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.WoParameterRepository
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.inventory.domain.NodeId

trait RoParameterService {

  /**
   * Returns a Global Parameter by its name
   */
  def getGlobalParameter(parameterName : ParameterName) : Box[Option[GlobalParameter]]
  
  /**
   * Returns all defined Global Parameters
   */
  def getAllGlobalParameters() : Box[Seq[GlobalParameter]]
  
  /**
   * Returns all parameters applicable for a given node
   */
  def getParametersByNode(nodeId: NodeId) : Box[Seq[Parameter]]
}

trait WoParameterService {
  /**
   * Save a parameter
   * Returns the new global Parameter
   * Will fail if a parameter with the same name exists
   */
  def saveParameter(
      parameter : GlobalParameter
    , modId     : ModificationId
    , actor     : EventActor
    , reason    : Option[String]
  ) : Box[GlobalParameter]
  
  /**
   * Updates a parameter
   * Returns the new global Parameter
   * Will fail if no params with the same name exists
   */
  def updateParameter(
      parameter : GlobalParameter
    , modId     : ModificationId
    , actor     : EventActor
    , reason    : Option[String]
   ) : Box[GlobalParameter]
  
  /**
   * Delete a global parameter
   */
  def delete(parameterName:ParameterName, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[ParameterName]
}

class RoParameterServiceImpl(
    roParamRepo : RoParameterRepository
) extends RoParameterService with Loggable {
  /**
   * Returns a Global Parameter by its name
   */
  def getGlobalParameter(parameterName : ParameterName) : Box[Option[GlobalParameter]] = {
    roParamRepo.getGlobalParameter(parameterName) match {
      case Full(entry) => Full(Some(entry))
      case Empty       => Full(None)
      case e:Failure   => 
        logger.error("Error while trying to fetch param %s : %s".format(parameterName.value, e.messageChain))
        e
    }
  }

  /**
   * Returns all defined Global Parameters
   */
  def getAllGlobalParameters() : Box[Seq[GlobalParameter]]= {
    roParamRepo.getAllGlobalParameters() match {
      case Full(seq) => Full(seq)
      case Empty       => Full(Seq())
      case e:Failure   => 
        logger.error("Error while trying to fetch all parameters : %s".format(e.messageChain))
        e
    }
  }
  
  /**
   * Returns all parameters applicable for a given node
   * Hyper naive implementation : all parameters !
   */
  def getParametersByNode(nodeId: NodeId) : Box[Seq[Parameter]] = {
    getAllGlobalParameters()
  }
}

class WoParameterServiceImpl(
    roParamService : RoParameterService
  , woParamRepo    : WoParameterRepository
  , asyncDeploymentAgent : AsyncDeploymentAgent
) extends WoParameterService with Loggable {
  /**
   * Save a parameter
   * Returns the new global Parameter
   * Will fail if a parameter with the same name exists
   */
  def saveParameter(
      parameter : GlobalParameter
    , modId     : ModificationId
    , actor     : EventActor
    , reason    : Option[String]
  ) : Box[GlobalParameter] = {
    woParamRepo.saveParameter(parameter, modId, actor, reason) match {
      case e:Failure => 
        logger.error("Error while trying to create param %s : %s".format(parameter.name.value, e.messageChain))
        e
      case Empty =>
        logger.error("Error : Empty result when trying to create param %s".format(parameter.name.value))
        Failure("Something unexpected happened when trying to create parameter %s".format(parameter.name.value))
      case Full(diff) =>
        // Ok, it's been save, try to fetch the new value
        roParamService.getGlobalParameter(parameter.name) match {
          case e: EmptyBox=> e
          case Full(option) =>
            option match {
              case Some(entry) =>
                logger.debug("Successfully created parameter %s".format(parameter.name.value))
                // launch a deployement
                asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
                Full(entry)
              case None =>
                logger.error("Could not fetch back newly created global parameter with name %s".format(parameter.name.value))
                Failure("Could not fetch back newly created global parameter with name %s".format(parameter.name.value))
            }
        }
    }
  }
  
    /**
   * Updates a parameter
   * Returns the new global Parameter
   * Will fail if no params with the same name exists
   */
  def updateParameter(
      parameter : GlobalParameter
    , modId     : ModificationId
    , actor     : EventActor
    , reason    : Option[String]
   ) : Box[GlobalParameter] = {
    woParamRepo.updateParameter(parameter, modId, actor, reason) match {
      case e:Failure => 
        logger.error("Error while trying to update param %s : %s".format(parameter.name.value, e.messageChain))
        e
      case Empty =>
        logger.error("Error : Empty result when trying to update param %s".format(parameter.name.value))
        Failure("Something unexpected happened when trying to update parameter %s".format(parameter.name.value))
      case Full(diff) =>
        // Ok, it's been updated, try to fetch the new value
        roParamService.getGlobalParameter(parameter.name) match {
          case e: EmptyBox=> e
          case Full(option) =>
            option match {
              case Some(entry) =>
                logger.debug("Successfully udated parameter %s".format(parameter.name.value))
                // launch a deployement
                asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
                Full(entry)
              case None =>
                logger.error("Could not fetch back updated global parameter with name %s".format(parameter.name.value))
                Failure("Could not fetch back updated global parameter with name %s".format(parameter.name.value))
            }
        }
    }
  }
  
  def delete(parameterName:ParameterName, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[ParameterName] = {
    woParamRepo.delete(parameterName, modId, actor, reason) match {
      case e:Failure => 
        logger.error("Error while trying to delete param %s : %s".format(parameterName.value, e.messageChain))
        e
      case Empty =>
        logger.error("Error : Empty result when trying to delete param %s".format(parameterName.value))
        Failure("Something unexpected happened when trying to update parameter %s".format(parameterName.value))
      case Full(diff) =>
        logger.debug("Successfully deleted parameter %s".format(parameterName.value))
        asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
        Full(parameterName)
    }
  }
}