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

package com.normation.rudder.web.services


import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository._
import net.liftweb.common._
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategory

/**
 * An utility service for Directive* trees.
 *
 * Allow to find node with logging, sorts nodes, etc
 *
 */
class JsTreeUtilService(
    activeTechniqueCategoryRepository: ActiveTechniqueCategoryRepository
  , activeTechniqueRepository:ActiveTechniqueRepository
  , directiveRepository:DirectiveRepository
  , techniqueRepository:TechniqueRepository
) {

    // get the Active Technique category, log on error
    def getActiveTechniqueCategory(id:ActiveTechniqueCategoryId,logger:Logger) : Option[ActiveTechniqueCategory] = {
      activeTechniqueCategoryRepository.getActiveTechniqueCategory(id) match {
        //remove sytem category
        case Full(cat) => if(cat.isSystem) None else Some(cat)
        case e:EmptyBox =>
          logger.error("Error when displaying category", e ?~! "Error while fetching Active Technique category %s".format(id))
          None
      }
    }

    // get the Active Technique, loqg on error
    def getActiveTechnique(id : ActiveTechniqueId,logger:Logger) : Option[(ActiveTechnique,Technique)] = {
      (for {
        activeTechnique <- activeTechniqueRepository.getActiveTechnique(id) ?~! "Error while fetching Active Technique %s".format(id)
        technique <- Box(techniqueRepository.getLastTechniqueByName(activeTechnique.techniqueName)) ?~!
              "Can not find referenced Technique '%s' in reference library".format(activeTechnique.techniqueName.value)
      } yield {
        (activeTechnique,technique)
      }) match {
        case Full(pair) => Some(pair)
        case e:EmptyBox =>
          val f = e ?~! "Error when trying to display Active Technique with id '%s' as a tree node".format(id)
          logger.error(f.messageChain)
          None
      }
    }

    // get the Directive, log on error
    def getPi(id:DirectiveId,logger:Logger) : Option[Directive] = directiveRepository.getDirective(id) match {
      case Full(directive) => Some(directive)
      case e:EmptyBox =>
        logger.error("Error while fetching node %s".format(id), e?~! "Error message was:")
        None
    }


    def getPtCategory(id:TechniqueCategoryId,logger:Logger) : Option[TechniqueCategory] = {
      techniqueRepository.getTechniqueCategory(id) match {
        //remove sytem category
        case Full(cat) => if(cat.isSystem) None else Some(cat)
        case e:EmptyBox =>
          val f = e ?~! "Error while fetching Technique category %s".format(id)
          logger.error(f.messageChain)
          None
      }
    }

    //check Technique existence and transform it to a tree node
    def getPt(name : TechniqueName,logger:Logger) : Option[Technique] = {
      techniqueRepository.getLastTechniqueByName(name).orElse {
        logger.error("Can not find Technique: " + name)
        None
      }
    }


    //
    // Different sorting
    //

    def sortPtCategory(x:TechniqueCategory,y:TechniqueCategory) : Boolean = {
      sort(x.name , y.name)
    }

    def sortActiveTechniqueCategory(x:ActiveTechniqueCategory,y:ActiveTechniqueCategory) : Boolean = {
      sort(x.name , y.name)
    }

    def sortPt(x:Technique,y:Technique) : Boolean = {
      sort(x.name , y.name)
    }

    def sortPi(x:Directive,y:Directive) : Boolean = {
      sort(x.name , y.name)
    }

    private[this] def sort(x:String,y:String) : Boolean = {
      if(String.CASE_INSENSITIVE_ORDER.compare(x,y) > 0) false else true
    }

}