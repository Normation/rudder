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


import com.normation.cfclerk.domain.PolicyPackage
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository._
import net.liftweb.common._
import com.normation.cfclerk.domain.PolicyPackageCategoryId
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.cfclerk.domain.PolicyPackageCategory
import com.normation.rudder.domain.policies.UserPolicyTemplateCategory

/**
 * An utility service for policy* trees. 
 * 
 * Allow to find node with logging, sorts nodes, etc
 *
 */
class JsTreeUtilService(
    userPolicyTemplateCategoryRepository: UserPolicyTemplateCategoryRepository
  , userPolicyTemplateRepository:UserPolicyTemplateRepository
  , policyInstanceRepository:PolicyInstanceRepository
  , policyPackageService:PolicyPackageService
) {

    // get the user policy template category, log on error
    def getUptCategory(id:UserPolicyTemplateCategoryId,logger:Logger) : Option[UserPolicyTemplateCategory] = {
      userPolicyTemplateCategoryRepository.getUserPolicyTemplateCategory(id) match {
        //remove sytem category
        case Full(cat) => if(cat.isSystem) None else Some(cat)
        case e:EmptyBox => 
          logger.error("Error when displaying category", e ?~! "Error while fetching user policy template category %s".format(id))
          None
      }
    }
    
    // get the user policy template, loqg on error
    def getUpt(id : UserPolicyTemplateId,logger:Logger) : Option[(UserPolicyTemplate,PolicyPackage)] = {
      (for {
        upt <- userPolicyTemplateRepository.getUserPolicyTemplate(id) ?~! "Error while fetching user policy template %s".format(id)
        pt <- Box(policyPackageService.getLastPolicyByName(upt.referencePolicyTemplateName)) ?~! 
              "Can not find referenced Policy Template '%s' in reference library".format(upt.referencePolicyTemplateName.value)
      } yield {
        (upt,pt)
      }) match {
        case Full(pair) => Some(pair)
        case e:EmptyBox => 
          val f = e ?~! "Error when trying to display user policy template with id '%s' as a tree node".format(id)
          logger.error(f.messageChain)
          None
      }
    }
    
    // get the policy instance, log on error
    def getPi(id:PolicyInstanceId,logger:Logger) : Option[PolicyInstance] = policyInstanceRepository.getPolicyInstance(id) match {
      case Full(pi) => Some(pi)
      case e:EmptyBox => 
        logger.error("Error while fetching node %s".format(id), e?~! "Error message was:")
        None
    }
  

    def getPtCategory(id:PolicyPackageCategoryId,logger:Logger) : Option[PolicyPackageCategory] = {
      policyPackageService.getPolicyTemplateCategory(id) match {
        //remove sytem category
        case Full(cat) => if(cat.isSystem) None else Some(cat)
        case e:EmptyBox => 
          val f = e ?~! "Error while fetching policy template category %s".format(id)
          logger.error(f.messageChain)
          None
      }
    }
    
    //check policy template existence and transform it to a tree node
    def getPt(name : PolicyPackageName,logger:Logger) : Option[PolicyPackage] = {
      policyPackageService.getLastPolicyByName(name).orElse {
        logger.error("Can not find policy template: " + name)
        None
      }
    }
    
    
    //
    // Different sorting
    //
    
    def sortPtCategory(x:PolicyPackageCategory,y:PolicyPackageCategory) : Boolean = {
      sort(x.name , y.name)
    }
    
    def sortUptCategory(x:UserPolicyTemplateCategory,y:UserPolicyTemplateCategory) : Boolean = {
      sort(x.name , y.name)
    }
    
    def sortPt(x:PolicyPackage,y:PolicyPackage) : Boolean = {
      sort(x.name , y.name)
    }

    def sortPi(x:PolicyInstance,y:PolicyInstance) : Boolean = {
      sort(x.name , y.name)
    }
    
    private[this] def sort(x:String,y:String) : Boolean = {
      if(String.CASE_INSENSITIVE_ORDER.compare(x,y) > 0) false else true
    }

}