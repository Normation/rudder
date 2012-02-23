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
import com.normation.rudder.domain.log.RudderEventActor

/**
 * That class add all the available reference template in 
 * the default user library 
 * if it wasn't already initialized.
 */
class CheckInitUserTemplateLibrary(
  rudderDit:RudderDit,
  ldap:LDAPConnectionProvider, 
  refTemplateService:TechniqueRepository, 
  userCategoryService:ActiveTechniqueCategoryRepository, 
  userTempalteService:ActiveTechniqueRepository
) extends BootstrapChecks with Loggable {

 
  override def checks() : Unit = {
    ldap.foreach { con =>
    
        con.get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, A_INIT_DATETIME, A_OC) match {
          case e:EmptyBox => logger.error("The root entry of the user template library was not found")
          case Full(root) => root.getAsGTime(A_INIT_DATETIME) match {
            case Some(date) => logger.debug("The root user template library was initialized on %s".format(date.dateTime.toString("YYYY/MM/dd HH:mm")))
            case None => 
              logger.info("The Active Technique library is not marked as being initialized: adding all policies from reference library...")
              copyReferenceLib(con) match {
                case Full(x) => logger.info("...done")
                case eb:EmptyBox =>
                  val e = eb ?~! "Some error where encountered during the initialization of the user library"
                  val msg = e.messageChain.split("<-").mkString("\n ->")
                  logger.warn(msg)
                  logger.debug(e.exceptionChain)
              }
              root += (A_OC, OC_ACTIVE_TECHNIQUE_LIB_VERSION)
              root +=! (A_INIT_DATETIME, GeneralizedTime(DateTime.now()).toString)
              con.save(root)
        }
      }
    }
  }

  /**
   * Actually copy from reference Directive lib to user lib.
   */
  private[this] def copyReferenceLib(con:LDAPConnection) : Box[AnyRef] = {
    def recCopyRef(fromCatId:TechniqueCategoryId, toParentCat:ActiveTechniqueCategory) : Box[ActiveTechniqueCategory] = {
        
      for {
        fromCat <- refTemplateService.getTechniqueCategory(fromCatId)
        newUserPTCat = ActiveTechniqueCategory(
            id = genUserCatId(fromCat)
          , name = fromCat.name
          , description = fromCat.description
          , children = Nil
          , items = Nil
        )
        res <- if(fromCat.isSystem) { //Rudder internal Technique category are handle elsewhere
            Full(newUserPTCat)
          } else {
            for {
              updatedParentCat <- userCategoryService.addActiveTechniqueCategory(newUserPTCat, toParentCat, RudderEventActor) ?~! 
                "Error when adding category '%s' to user library parent category '%s'".format(newUserPTCat.id.value, toParentCat.id.value)
                //now, add items and subcategories, in a "try to do the max you can" way
                fullRes <- boxSequence(
                  //Techniques
                  bestEffort(fromCat.packageIds.groupBy(id => id.name).toSeq) { case (name, ids) =>
                    for {
                      activeTechnique <- userTempalteService.addTechniqueInUserLibrary(newUserPTCat.id, name, ids.map( _.version).toSeq, RudderEventActor) ?~!
                        "Error when adding Technique '%s' into user library category '%s'".format(name.value, newUserPTCat.id.value)
                    } yield {
                      activeTechnique
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
    bestEffort(refTemplateService.getTechniqueLibrary.subCategoryIds.toSeq) { id =>
      recCopyRef(id, userCategoryService.getActiveTechniqueLibrary)
    }    
  }
  
  private[this] def genUserCatId(fromCat:TechniqueCategory) : ActiveTechniqueCategoryId = {
      //for the technique ID, use the last part of the path used for the cat id.
      ActiveTechniqueCategoryId("userlib_" + fromCat.id.name.value)
  }
  
}
