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

package bootstrap.liftweb
package checks

import com.normation.rudder.domain.RudderDit
import com.normation.ldap.sdk._
import net.liftweb.common._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services._
import com.normation.rudder.repository._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.policies._
import com.normation.utils.Control._
import org.joda.time.DateTime
import com.normation.inventory.ldap.core.LDAPConstants.A_OC

/**
 * That class add all the available reference template in 
 * the default user library 
 * if it wasn't already initialized.
 */
class CheckInitUserTemplateLibrary(
  rudderDit:RudderDit,
  ldap:LDAPConnectionProvider, 
  refTemplateService:PolicyPackageService, 
  userCategoryService:UserPolicyTemplateCategoryRepository, 
  userTempalteService:UserPolicyTemplateRepository
) extends BootstrapChecks with Loggable {

 
  override def checks() : Unit = {
    ldap.foreach { con =>
    
        con.get(rudderDit.POLICY_TEMPLATE_LIB.dn, A_INIT_DATETIME, A_OC) match {
          case e:EmptyBox => logger.error("The root entry of the user template library was not found")
          case Full(root) => root.getAsGTime(A_INIT_DATETIME) match {
            case Some(date) => logger.debug("The root user template library was initialized on %s".format(date.dateTime.toString("YYYY/MM/dd HH:mm")))
            case None => 
              logger.info("The user policy template library is not marked as being initialized: adding all policies from reference library...")
              copyReferenceLib(con) match {
                case Full(x) => logger.info("...done")
                case e:EmptyBox =>
                  val msg = (e ?~! "Some error where encountered during the initialization of the user library").messageChain.split("<-").mkString("\n ->")
                  logger.warn(msg)
              }
              root += (A_OC, OC_USER_LIB_VERSION)
              root +=! (A_INIT_DATETIME, GeneralizedTime(new DateTime()).toString)
              con.save(root)
        }
      }
    }
  }

  /**
   * Actually copy from reference policy lib to user lib.
   */
  private[this] def copyReferenceLib(con:LDAPConnection) : Box[AnyRef] = {
    def recCopyRef(fromCatId:PolicyPackageCategoryId, toParentCat:UserPolicyTemplateCategory) : Box[UserPolicyTemplateCategory] = {
        
      for {
        fromCat <- refTemplateService.getPolicyTemplateCategory(fromCatId)
        newUserPTCat = UserPolicyTemplateCategory(
            id = genUserCatId(fromCat)
          , name = fromCat.name
          , description = fromCat.description
          , children = Nil
          , items = Nil
        )
        res <- if(fromCat.isSystem) { //system policy template category are handle elsewhere
            Full(newUserPTCat)
          } else {
            for {
              updatedParentCat <- userCategoryService.addUserPolicyTemplateCategory(newUserPTCat, toParentCat) ?~! 
                "Error when adding category '%s' to user library parent category '%s'".format(newUserPTCat.id.value, toParentCat.id.value)
                //now, add items and subcategories, in a "try to do the max you can" way
                fullRes <- boxSequence(
                  //policy templates
                  bestEffort(fromCat.packageIds.groupBy(id => id.name).toSeq) { case (name, ids) =>
                    for {
                      upt <- userTempalteService.addPolicyTemplateInUserLibrary(newUserPTCat.id, name, ids.map( _.version).toSeq ) ?~!
                        "Error when adding Policy Template '%s' into user library category '%s'".format(name.value, newUserPTCat.id.value)
                    } yield {
                      upt
                    }
                  } ::
                  //recurse on children categories of reference lib
                  bestEffort(fromCat.subCategoryIds.toSeq) { catId => recCopyRef(catId, newUserPTCat) } ::
                  Nil
                )
            } yield {
              fullRes
            }
          }
      } yield {
        newUserPTCat
      }
    }

    //apply with root cat children ids
    bestEffort(refTemplateService.getReferencePolicyTemplateLibrary.subCategoryIds.toSeq) { id =>
      recCopyRef(id, userCategoryService.getUserPolicyTemplateLibrary)
    }    
  }
  
  private[this] def genUserCatId(fromCat:PolicyPackageCategory) : UserPolicyTemplateCategoryId = {
      //for the pt ID, use the last part of the path used for the cat id.
      UserPolicyTemplateCategoryId("userlib_" + fromCat.id.name.value)
  }
  
}
