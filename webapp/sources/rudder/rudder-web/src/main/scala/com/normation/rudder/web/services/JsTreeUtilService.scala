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
import com.normation.cfclerk.domain.TechniqueName

import com.normation.box._

/**
 * An utility service for Directive* trees.
 *
 * Allow to find node with logging, sorts nodes, etc
 *
 */
class JsTreeUtilService(
    directiveRepository:RoDirectiveRepository
  , techniqueRepository:TechniqueRepository
) {

    // get the Active Technique category, log on error
    def getActiveTechniqueCategory(id:ActiveTechniqueCategoryId,logger:Logger) : Option[ActiveTechniqueCategory] = {
      directiveRepository.getActiveTechniqueCategory(id).toBox match {
        //remove sytem category
        case Full(cat) => if(cat.isSystem) None else Some(cat)
        case e:EmptyBox =>
          logger.error("Error when displaying category", e ?~! "Error while fetching Active Technique category %s".format(id))
          None
      }
    }

    // get the Active Technique, log on error
    def getActiveTechnique(id : ActiveTechniqueId,logger:Logger) : Box[(ActiveTechnique, Option[Technique])] = {
      (for {
        activeTechnique <- directiveRepository.getActiveTechnique(id).toBox.flatMap { Box(_) } ?~! "Error while fetching Active Technique %s".format(id)
      } yield {
        (activeTechnique, techniqueRepository.getLastTechniqueByName(activeTechnique.techniqueName))
      }) match {
        case Full(pair) => Some(pair)
        case e:EmptyBox =>
          val f = e ?~! "Error when trying to display Active Technique with id '%s' as a tree node".format(id)
          logger.error(f.messageChain)
          None
      }
    }

    // get the Directive, log on error
    def getPi(id:DirectiveId,logger:Logger) : Option[Directive] = directiveRepository.getDirective(id).toBox match {
      case Full(directive) => directive
      case e:EmptyBox =>
        logger.error("Error while fetching node %s".format(id), e?~! "Error message was:")
        None
    }


    def getPtCategory(id:TechniqueCategoryId,logger:Logger) : Option[TechniqueCategory] = {
      techniqueRepository.getTechniqueCategory(id).toBox match {
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

    def sortFullActiveTechniqueCategory(x: FullActiveTechniqueCategory,y: FullActiveTechniqueCategory) : Boolean = {
      sort(x.name , y.name)
    }

    def sortFullActiveTechnique(x: FullActiveTechnique,y: FullActiveTechnique) : Boolean = {
      sort(x.techniqueName.value , y.techniqueName.value)
    }

    def sortPt(x:TechniqueName, y:TechniqueName) : Boolean = {
      sort(x.value , y.value)
    }

    def sortPi(x:Directive,y:Directive) : Boolean = {
      sort(x.name , y.name)
    }

    private[this] def sort(x:String,y:String) : Boolean = {
      if(String.CASE_INSENSITIVE_ORDER.compare(x,y) > 0) false else true
    }

}
