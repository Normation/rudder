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

package com.normation.cfclerk.services.impl


import com.normation.cfclerk.domain._
import com.normation.cfclerk.exceptions._

import net.liftweb.common._
import com.normation.cfclerk.services._
import com.normation.utils.Control.sequence

class Cf3PolicyDraftContainerServiceImpl(
    cf3PromisesFileWriterService: Cf3PromisesFileWriterService
  , techniqueRepository            : TechniqueRepository
) extends Cf3PolicyDraftContainerService with Loggable {

 /**
   * Create a container
   * @param identifier
   * @param policiesInstancesBeans
   * @return
   */
  def createContainer(identifier: String, parameters: Set[ParameterEntry], cf3PolicyDrafts: Seq[Cf3PolicyDraft]) : Box[Cf3PolicyDraftContainer] = {

      if (cf3PolicyDrafts.size==0) {
        logger.error("There should be at least one CF3 policy draft to configure the container")
        return ParamFailure[Seq[Cf3PolicyDraft]]("No CF3 policy draft", Full(new NotFoundException("No CF3 policy draft defined")), Empty, cf3PolicyDrafts)
      }

      val container = new Cf3PolicyDraftContainer(
          identifier
        , parameters
      )

      for {
       res <- sequence(cf3PolicyDrafts) { policytoAdd =>
         addPolicy(container, policytoAdd)
       }
      } yield {
        container
      }
  }


  /**
   * Add a CF3 Policy Draft to a container
   * @param container
   * @param directiveBean
   * @return
   */
  def addCf3PolicyDraft(container: Cf3PolicyDraftContainer, cf3PolicyDraft : Cf3PolicyDraft) : Box[Cf3PolicyDraft] = {
    addPolicy(container, cf3PolicyDraft)
  }


  /**
   * Adding a policy to a container
   */
  private def addPolicy(container: Cf3PolicyDraftContainer, cf3PolicyDraft : Cf3PolicyDraft) : Box[Cf3PolicyDraft] = {

    // check the legit character of the policy
    if (container.get(cf3PolicyDraft.id) != None) {
      logger.warn("Cannot add a CF3 Policy Draft with the same id than an already existing one " + cf3PolicyDraft.id)
      return ParamFailure[Cf3PolicyDraft]("Duplicate CF3 Policy Draft", Full(new TechniqueException("Duplicate CF3 Policy Draft " +cf3PolicyDraft.id)), Empty, cf3PolicyDraft)
    }

    val policy = cf3PolicyDraft.technique
    if (container.findById(cf3PolicyDraft.technique.id).filter(x => policy.isMultiInstance==false).size>0) {
      logger.warn("Cannot add a CF3 Policy Draft from the same non duplicable policy than an already existing one " + cf3PolicyDraft.technique.id)
      return ParamFailure[Cf3PolicyDraft]("Duplicate unique policy", Full(new TechniqueException("Duplicate unique policy " +cf3PolicyDraft.technique.id)), Empty, cf3PolicyDraft)
    }

    container.add(cf3PolicyDraft) flatMap { cf3pd =>
      //check that directive really is in container
      if(container.get(cf3pd.id).isDefined) {
        logger.info("Successfully added CF3 Policy Draft %s to container %s".format(cf3PolicyDraft, container.outPath))
        Full(cf3pd)
      } else {
        logger.error("An error occured while adding policy %s for container %s : it is not present".format( cf3PolicyDraft.id, container.outPath))
        Failure("Something bad happened, the CF3 Policy Draft '%s' should have been added in container with out path '%s'".format(cf3pd.id, container.outPath))
        }
    }
  }
}