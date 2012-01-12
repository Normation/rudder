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

package com.normation.rudder.repository

import java.io.File
import java.io.FileInputStream
import scala.annotation.serializable
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.rudder.domain.policies.UserPolicyTemplateCategory
import com.normation.rudder.services.marshalling.PolicyInstanceUnserialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateCategoryUnserialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateUnserialisation
import com.normation.utils.Control._
import com.normation.utils.ScalaReadWriteLock
import com.normation.utils.UuidRegex
import com.normation.utils.XmlUtils
import net.liftweb.common._
import com.normation.rudder.domain.RudderDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.unboundid.ldap.sdk.DN
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import com.normation.ldap.sdk.LDAPConnection
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.unboundid.ldap.sdk.RDN
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.ldap.sdk.GeneralizedTime
import com.normation.rudder.domain.policies.UserPolicyTemplateCategoryId
import com.normation.rudder.domain.policies.UserPolicyTemplateId
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.cfclerk.domain.PolicyPackageName

/**
 * A category of the policy library. 
 * 
 */
case class UptCategoryContent(
    category  : UserPolicyTemplateCategory
  , categories: Set[UptCategoryContent]
  , templates : Set[UptContent]
)

case class UptContent(
    upt : UserPolicyTemplate
  , pis : Set[PolicyInstance]
)

/**
 * Identifier for user library archive
 */
case class UserPolicyLibraryArchiveId(value:String)

/**
 * That trait allows to manage the import of user policy template library 
 * (categories, templates, policy instances) from the File System into
 * the LDAP. 
 */
trait ParsePolicyLibrary {

  /**
   * That method parse an user policy library from the
   * file system. 
   */
  def parse : Box[UptCategoryContent]
}

trait ImportPolicyLibrary {  
  /**
   * That method swap an existing user policy library in LDAP
   * to a new one. 
   * 
   * In case of error, we try to restore the old policy library. 
   */
  def swapUserPolicyLibrary(rootCategory:UptCategoryContent, includeSystem:Boolean = false) : Box[Unit]
}


class ParsePolicyLibraryImpl(
    categoryUnserialiser: UserPolicyTemplateCategoryUnserialisation
  , uptUnserialiser     : UserPolicyTemplateUnserialisation
  , piUnserialiser      : PolicyInstanceUnserialisation
  , userLibRootDirectory: File
  , uptcFileName        : String = "category.xml"
  , uptFileName         : String = "userPolicyTemplateSettings.xml"    
) extends ParsePolicyLibrary {
  
  /**
   * Parse the use policy template library. 
   * The structure is purely recursive:
   * - a directory must contains either category.xml or userPolicyTemplateSettings.xml
   * - the root directory contains category.xml
   * - a directory with a category.xml file must contains only sub-directories (ignore files)
   * - a directory containing userPolicyTemplateSettings.xml may contain UUID.xml files (ignore sub-directories and other files)
   */
  def parse : Box[UptCategoryContent] = {
    def recParseDirectory(directory:File) : Box[Either[UptCategoryContent, UptContent]] = {

      val category = new File(directory, uptcFileName)
      val template = new File(directory, uptFileName)

      (category.exists, template.exists) match {
        //herrr... skip that one
        case (false, false) => Empty
        case (true, true) => Failure("The directory '%s' contains both '%s' and '%s' descriptor file. Only one of them is authorized".format(directory.getPath, uptcFileName, uptFileName))
        case (true, false) =>
          // that's the directory of an UserPolicyTemplateCategory. 
          // ignore files other than uptcFileName (parsed as an UserPolicyTemplateCategory), recurse on sub-directories
          // don't forget to sub-categories and UPT and UPTC
          for {
            uptcXml  <- XmlUtils.parseXml(new FileInputStream(category), Some(category.getPath)) ?~! "Error when parsing file '%s' as a category".format(category.getPath)
            uptc     <- categoryUnserialiser.unserialise(uptcXml) ?~! "Error when unserializing category for file '%s'".format(category.getPath)
            subDirs  =  {
                          val files = directory.listFiles
                          if(null != files) files.filter( f => f != null && f.isDirectory).toSeq
                          else Seq()
                        }
            subItems <- sequence(subDirs) { dir =>
                          recParseDirectory(dir)
                        }
          } yield {
            val subCats = subItems.collect { case Left(x) => x }.toSet
            val upts = subItems.collect { case Right(x) => x }.toSet
            
            val category = uptc.copy(
                children = subCats.map { case UptCategoryContent(cat, _, _) => cat.id }.toList
              , items = upts.map { case UptContent(upt, _) => upt.id}.toList
            )
            
            Left(UptCategoryContent(category, subCats, upts))
          }
          
        case (false, true) => 
          // that's the directory of an UserPolicyTemplate
          // ignore sub-directories, parse uptFileName as an UserPolicyTemplate, parse UUID.xml as PI
          // don't forget to add PI ids to UPT
          for {
            uptXml  <- XmlUtils.parseXml(new FileInputStream(template), Some(template.getPath)) ?~! "Error when parsing file '%s' as a category".format(template.getPath)
            upt     <- uptUnserialiser.unserialise(uptXml) ?~! "Error when unserializing template for file '%s'".format(template.getPath)
            subDirs =  {
                         val files = directory.listFiles
                         if(null != files) files.filter( f => f != null && UuidRegex.isValid(f.getName)).toSeq
                         else Seq()
                       }
            pis     <- sequence(subDirs) { piFile =>
                         for {
                           piXml      <-  XmlUtils.parseXml(new FileInputStream(piFile), Some(piFile.getPath)) ?~! "Error when parsing file '%s' as a policy instance".format(piFile.getPath)
                           (_, pi, _) <-  piUnserialiser.unserialise(piXml) ?~! "Error when unserializing ppolicy instance for file '%s'".format(piFile.getPath)
                         } yield {
                           pi
                         }
                       }
          } yield {
            val pisSet = pis.toSet
            Right(UptContent(
                upt.copy(policyInstances = pisSet.map(_.id).toList)
              , pisSet
            ))
          }
      }
    }
    
    recParseDirectory(userLibRootDirectory) match {
      case Full(Left(x)) => Full(x)
      
      case Full(Right(x)) => 
        Failure("We found an User Policy Template where we were expected the root of user policy library, and so a category. Path: '%s'; found: '%s'".format(
            userLibRootDirectory, x.upt))
      
      case Empty => Failure("Error when parsing the root directory for policy library '%s'. Perhaps the '%s' file is missing in that directory, or the saved policy library was not correctly exported".format(
                      userLibRootDirectory, uptcFileName
                    ) )
                    
      case f:Failure => f
    }
  }
}

class ImportPolicyLibraryImpl(
    rudderDit   : RudderDit
  , ldap        : LDAPConnectionProvider
  , mapper      : LDAPEntityMapper
  , userLibMutex: ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends ImportPolicyLibrary with Loggable {
  
  
  /**
   * That method swap an existing user policy library in LDAP
   * to a new one. 
   * 
   * In case of error, we try to restore the old policy library. 
   */
  def swapUserPolicyLibrary(rootCategory:UptCategoryContent, includeSystem:Boolean = false) : Box[Unit] = {
    /*
     * Hight level behaviour:
     * - check that User Library respects global rules
     *   (at most one UPT for each PT, no two categories with the same name, etc)
     *   If not, remove duplicates with error logs (because perhaps they are no duplicate, 
     *   user will want to know)
     * - move current user lib elsewhere in the LDAP 
     *   (with the root and the system)
     * - create back the root, with
     *   - initTimeStamp to now
     *   - the same referenceLibraryVersion
     * - copy back system if kept 
     * - save all categories, PT, PIs
     * - if everything goes well, delete the old User Lob 
     * - else, rollback: delete new User lib, move back old user lib
     * 
     */    
    
    //as far atomic as we can :)
    //don't bother with system and consistency here, it is taken into account elsewhere
    def atomicSwap(userLib:UptCategoryContent) : Box[UserPolicyLibraryArchiveId] = {
      //save the new one
      //we need to keep the git commit id
      def saveUserLib(con:LDAPConnection, userLib:UptCategoryContent, gitId:Option[String]) : Box[Unit] = {
        def recSaveUserLib(parentDN:DN, content:UptCategoryContent, isRoot:Boolean = false) : Box[Unit] = {
          //start with the category
          //then with pt/pi for that category
          //then recurse on sub-categories
          val categoryEntry = mapper.userPolicyTemplateCategory2ldap(content.category, parentDN)
          if(isRoot) {
            categoryEntry +=  (A_OC, OC_USER_LIB_VERSION)
            categoryEntry +=! (A_INIT_DATETIME, GeneralizedTime(DateTime.now()).toString)
            gitId.foreach { x => categoryEntry +=! (A_REF_LIB_VERSION, x) }
          }
          
          for {
            category      <- con.save(categoryEntry) ?~! "Error when persisting category with DN '%s' in LDAP".format(categoryEntry.dn)
            pts           <- sequence(content.templates.toSeq) { case UptContent(upt, pis) => 
                               val uptEntry = mapper.userPolicyTemplate2Entry(upt, categoryEntry.dn)
                               for {
                                 uptSaved <- con.save(uptEntry) ?~! "Error when persisting User Policy entry with DN '%s' in LDAP".format(uptEntry.dn)
                                 pisSaved <- sequence(pis.toSeq) { pi => 
                                               val piEntry = mapper.userPolicyInstance2Entry(pi, uptEntry.dn)
                                               con.save(piEntry,true) ?~! "Error when persisting Policy Instance entry with DN '%s' in LDAP".format(piEntry.dn)
                                             }
                               } yield {
                                 "OK"
                               }
                             }
            subCategories <- sequence(content.categories.toSeq) { cat => 
                               recSaveUserLib(categoryEntry.dn, cat)
                             }
          } yield {
            "OK"
          }
        }
        
        recSaveUserLib(rudderDit.POLICY_TEMPLATE_LIB.dn.getParent,userLib, isRoot = true)
      }
      
      //move user lib to archive branch
      def moveToArchive(connection:LDAPConnection) : Box[UserPolicyLibraryArchiveId] = {
        val archiveId = UserPolicyLibraryArchiveId(DateTime.now().toString(ISODateTimeFormat.dateTime))
        val dn = rudderDit.ARCHIVES.userLibDN(archiveId)
        for {
          ok <- connection.move(rudderDit.POLICY_TEMPLATE_LIB.dn, dn.getParent, Some(dn.getRDN)) ?~! "Error when arching current User Policy Tempalte Library with DN '%s' to LDAP".format(dn)
        } yield {
          archiveId
        }
      }
      
      //copy back system categories/PT/PIs is includeSystem is FALSE 
      def copyBackSystemEntrie(con:LDAPConnection, id:UserPolicyLibraryArchiveId) : Box[Unit] = {
        //the only hard part is for system pts in non system categories, because
        //we may miss a parent. But it should not be allowed, so we consider such cases
        //as errors
        import com.normation.ldap.sdk._
        import com.normation.ldap.sdk.BuildFilter.EQ

        
        val archiveRootDN = rudderDit.ARCHIVES.userLibDN(id)
        
        //a method that change the root of a dn from archive to user lib root
        def setUserLibRoot(dn:DN) : Option[DN] = {
          def recParent(x:DN) : Option[List[RDN]] = {
            if(null == x) None
            else if(x == archiveRootDN) Some(Nil)
            else recParent(x.getParent).map( x.getRDN :: _ )
          }
          
          def recBuildDN(root:DN, rdns:List[RDN]) : DN = rdns match {
            case Nil => root
            case h::t => recBuildDN(new DN(h,root),t)
          }
          
          val relatives = recParent(dn)
          relatives.map( rdns => recBuildDN(rudderDit.POLICY_TEMPLATE_LIB.dn, rdns.reverse))
        }
        
        val entries = con.searchSub(archiveRootDN, EQ(A_IS_SYSTEM,true.toLDAPString))
        val allDNs  =  entries.map( _.dn ).toSet
        //update DN to UserLib DN, remove root entry and entries without parent in that set
        val updatedDNEntries =  (entries.collect {
                                  case entry if(entry.dn == archiveRootDN) => 
                                    logger.trace("Skipping root entry, already taken into account")
                                    None
                                  case entry if(allDNs.exists( _ == entry.dn.getParent)) =>
                                    //change the DN to user lib
                                    setUserLibRoot(entry.dn) match {
                                      case None => 
                                        logger.error("Ignoring entry with DN '%s' because it does not belong to archive '%s'".format(entry.dn, archiveRootDN))
                                        None
                                      case Some(dn) => 
                                        Some(LDAPEntry(dn, entry.attributes))
                                    }
                                  case entry =>
                                    logger.error("Error when trying to save entry '%s' marked as system: its parent is not available, perhaps it is not marked as system?".format(entry.dn))
                                    None
                                }).flatten
                                
         //actually save system entries in User Lib
         (sequence(updatedDNEntries.sortWith( (x,y) => DN.compare(x.dn.toString, y.dn.toString) < 0)) { 
           entry => con.save(entry) ?~! "Error when copying back system entry '%s' from archive '%s'".format(entry.dn, archiveRootDN)
         }).map { x => ; /*unit*/}
      }
      
      //restore in case of error
      def restoreArchive(con:LDAPConnection, id:UserPolicyLibraryArchiveId) : Box[Unit] = {
        val dn = rudderDit.ARCHIVES.userLibDN(id)
        for {
          delete    <- if(con.exists(rudderDit.POLICY_TEMPLATE_LIB.dn)) {
                         con.delete(rudderDit.POLICY_TEMPLATE_LIB.dn)
                       } else Full("ok")
          movedBack <- con.move(dn, rudderDit.POLICY_TEMPLATE_LIB.dn.getParent, Some(rudderDit.POLICY_TEMPLATE_LIB.dn.getRDN))
        } yield {
          movedBack
        }
      }
      
      //the sequence of operation to actually perform the swap with rollback
      for {
        con      <- ldap
        gitId    <- con.get(rudderDit.POLICY_TEMPLATE_LIB.dn, OC_USER_LIB_VERSION).map { entry => 
                      entry(OC_USER_LIB_VERSION)
                    } ?~! "Error when looking for the root entry of the User Policy Template Library when trying to check for an existing revision number"
        archived <- moveToArchive(con)
        finished <- {
                      (for {
                        saved  <- saveUserLib(con, userLib, gitId)
                        system <-if(includeSystem) Full("OK") 
                                   else copyBackSystemEntrie(con, archived) ?~! "Error when copying back system entries in the imported policy library"
                      } yield {
                        system
                      }) match {
                           case Full(unit)  => Full(unit)
                           case eb:EmptyBox => 
                             logger.error("Error when trying to load archived User Policy Library. Rollbaching to previous one.")
                             restoreArchive(con, archived) match {
                               case eb2: EmptyBox => eb ?~! "Error when trying to restore archive with ID '%s' for the user policy template library".format(archived.value)
                               case Full(_) => eb ?~! "Error when trying to load archived User Policy Library. A rollback to previous state was executed"
                             }
                             
                      }
                    }
      } yield {
        archived
      }
    }
    
    /**
     * Check that the user lib match our global rules:
     * - two UPT can't referenced the same PT (arbitrary skip the second one)
     * - two categories can not have the same name (arbitrary skip the second one)
     * - all ids must be uniques
     * + remove system library if we don't want them
     */
    def checkUserLibConsistance(userLib:UptCategoryContent) : Box[UptCategoryContent] = {
      import scala.collection.mutable.{Set,Map}
      val piIds = Set[PolicyInstanceId]()
      val ptNames = Map[PolicyPackageName,UserPolicyTemplateId]()
      val uptIds = Set[UserPolicyTemplateId]()
      val categoryIds = Set[UserPolicyTemplateCategoryId]()
      val categoryNames = Map[String, UserPolicyTemplateCategoryId]()
      
      def sanitizeUPT(uptContent:UptContent) : Option[UptContent] = {
        val upt = uptContent.upt
        if(upt.isSystem && includeSystem == false) None
        else if(uptIds.contains(upt.id)) {
          logger.error("Ignoring User Policy Template because is ID was already processed: " + upt)
          None
        } else ptNames.get(upt.referencePolicyTemplateName) match {
          case Some(id) =>
            logger.error("Ignoring User Policy Template with ID '%s' because it references policy template with name '%s' already referenced by user policy template with ID '%s'".format(
                upt.id.value, upt.referencePolicyTemplateName.value, id.value
            ))
            None
          case None => //OK, proccess PIs !
            val sanitizedPis = uptContent.pis.flatMap { pi =>
              if(pi.isSystem && includeSystem == false) None
              else if(piIds.contains(pi.id)) {
                logger.error("Ignoring following PI because an other PI with the same ID was already processed: " + pi)
                None
              } else {
                piIds += pi.id
                Some(pi)
              }
            }
            
            Some(uptContent.copy(
                upt = upt.copy(policyInstances = sanitizedPis.toList.map( _.id ))
              , pis = sanitizedPis
            ))
        }
      }
      
      def recSanitizeCategory(content:UptCategoryContent, isRoot:Boolean = false) : Option[UptCategoryContent] = {
        val cat = content.category
        if( !isRoot && content.category.isSystem && includeSystem == false) None
        else if(categoryIds.contains(cat.id)) {
          logger.error("Ignoring User Policy Template Category because its ID was already processed: " + cat)
          None
        } else if(cat.name == null || cat.name.size < 1) {
          logger.error("Ignoring User Policy Template Category because its name is empty: " + cat)
          None
        } else categoryNames.get(cat.name) match { //name is mandatory
          case Some(id) =>
            logger.error("Ignoring User Policy Template Categor with ID '%s' because its name is '%s' already referenced by category with ID '%s'".format(
                cat.id.value, cat.name, id.value
            ))
            None
          case None => //OK, process PT and sub categories !
            categoryIds += cat.id
            categoryNames += (cat.name -> cat.id)
            
            val subCategories = content.categories.flatMap( recSanitizeCategory(_) )
            val subUPTs = content.templates.flatMap( sanitizeUPT(_) )
            
            Some(content.copy(
                category  = cat.copy(
                                children = subCategories.toList.map( _.category.id )
                              , items = subUPTs.toList.map( _.upt.id )
                            )
              , categories = subCategories.toSet
              , templates  = subUPTs.toSet
            ))
        }
      }
      
      Box(recSanitizeCategory(userLib, true)) ?~! "Error when trying to sanitize serialised user library for consistency errors"
    }
        
    //all the logic for a library swap.
    for {
      cleanLib <- checkUserLibConsistance(rootCategory)
      moved    <- userLibMutex.writeLock { atomicSwap(cleanLib) } ?~! "Error when swapping serialised library and existing one in LDAP"
    } yield {
      //delete archive - not a real error if fails
      val dn = rudderDit.ARCHIVES.userLibDN(moved)
      (for {
        con     <- ldap
        deleted <- con.delete(dn)
      } yield {
        deleted
      }) match {
        case eb:EmptyBox => 
          logger.warn("Error when deleting archived library in LDAP with DN '%s'".format(dn))
        case _ => //
      }
      moved
    }
    
  }
}