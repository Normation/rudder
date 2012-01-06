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

package com.normation.rudder.repository.xml

import java.io.FileInputStream
import java.util.regex.Pattern
import scala.collection.JavaConversions.collectionAsScalaIterable
import org.apache.commons.io.FileUtils
import com.normation.rudder.repository.ArchiveId
import com.normation.rudder.repository.ConfigurationRuleRepository
import com.normation.rudder.repository.GitConfigurationRuleArchiver
import com.normation.rudder.repository.ItemArchiveManager
import com.normation.rudder.services.marshalling.ConfigurationRuleUnserialisation
import com.normation.utils.Control._
import com.normation.utils.UuidRegex
import com.normation.utils.XmlUtils
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Loggable
import net.liftweb.util.Helpers.tryo
import com.normation.rudder.repository.UserPolicyTemplateRepository
import com.normation.rudder.repository.GitUserPolicyTemplateArchiver
import com.normation.rudder.repository.UserPolicyTemplateCategoryRepository
import com.normation.rudder.repository.GitUserPolicyTemplateCategoryArchiver
import com.normation.rudder.repository.CategoryAndUPT


class ItemArchiveManagerImpl(
    configurationRuleRepository          : ConfigurationRuleRepository
  , utpCategoryRepository                : UserPolicyTemplateCategoryRepository
  , uptRepository                        : UserPolicyTemplateRepository
  , configurationRuleUnserialisation     : ConfigurationRuleUnserialisation
  , gitConfigurationRuleArchiver         : GitConfigurationRuleArchiver
  , gitUserPolicyTemplateCategoryArchiver: GitUserPolicyTemplateCategoryArchiver
  , gitUserPolicyTemplateArchiver        : GitUserPolicyTemplateArchiver
) extends ItemArchiveManager with Loggable {
  
  ///// implementation /////
  
  def saveAll(includeSystem:Boolean = false): Box[ArchiveId] = { 
    for {
      saveCrs     <- saveConfigurationRules(includeSystem)
      saveUserLib <- saveUserPolicyLibrary(includeSystem)
    } yield {
      saveUserLib
    }
  }
  
  private[this] def saveConfigurationRules(includeSystem:Boolean = false): Box[ArchiveId] = { 
    for {
      crs         <- configurationRuleRepository.getAll(false)
      cleanedRoot <- tryo { FileUtils.cleanDirectory(gitConfigurationRuleArchiver.getRootDirectory) }
      saved       <- sequence(crs) { cr => 
                       gitConfigurationRuleArchiver.archiveConfigurationRule(cr,false)
                     }
      commitId    <- gitConfigurationRuleArchiver.commitConfigurationRules
    } yield {
      ArchiveId(commitId)
    }
  }
  
  private[this] def saveUserPolicyLibrary(includeSystem:Boolean = false): Box[ArchiveId] = { 
    for { //ca ne marche pas, il faut les parents, d'ou le rec ci dessous
      catWithUPT   <- uptRepository.getUPTbyCategory(includeSystem)
      cats = catWithUPT.toArray
      //remove systems things if asked (both system categories and system upts in non-system categories)
      okCatWithUPT =  if(includeSystem) catWithUPT
                      else catWithUPT.collect { 
                          //always include root category, even if it's a system one
                          case (categories, CategoryAndUPT(cat, upts)) if(cat.isSystem == false || categories.size <= 1) => 
                            (categories, CategoryAndUPT(cat, upts.filter( _.isSystem == false )))
                      }
      cleanedRoot <- tryo { FileUtils.cleanDirectory(gitUserPolicyTemplateCategoryArchiver.getRootDirectory) }
      savedItems  <- sequence(okCatWithUPT.toSeq) { case (categories, CategoryAndUPT(cat, upts)) => 
                       val foo = "bar"
                       for {
                         //categories.tail is OK, as no category can have an empty path (id)
                         savedCat  <- gitUserPolicyTemplateCategoryArchiver.archiveUserPolicyTemplateCategory(cat,categories.reverse.tail, gitCommit = false)
                         savedUpts <- sequence(upts.toSeq) { upt =>
                                        gitUserPolicyTemplateArchiver.archiveUserPolicyTemplate(upt,categories.reverse, gitCommit = false)
                                      }
                       } yield {
                         "OK"
                       }
                     }
      commitId    <- gitUserPolicyTemplateCategoryArchiver.commitUserPolicyLibrary
    } yield {
      ArchiveId(commitId)
    }
  }
  
  def importLastArchive(includeSystem:Boolean = false) : Box[Unit] = {
    
    for {
      files <- tryo { FileUtils.listFiles(gitConfigurationRuleArchiver.getRootDirectory,null,false).filter { f => isXmlUuid(f.getName) } }
      xmls  <- sequence(files.toSeq) { file =>
                 XmlUtils.parseXml(new FileInputStream(file), Some(file.getPath))
               }
      crs   <- sequence(xmls) { xml =>
                 configurationRuleUnserialisation.unserialise(xml)
               }
      swap  <- configurationRuleRepository.swapConfigurationRules(crs)
    } yield {
      //try to clean
      configurationRuleRepository.deleteSavedCr(swap) match {
        case eb:EmptyBox =>
          val e = eb ?~! ("Error when trying to delete saved archive of old cr: " + swap)
          logger.error(e)
        case _ => //ok
      }
      crs
    }
  }
  
  ///// utility methods /////
    
  private[this] val xmlUuidPattern = Pattern.compile(UuidRegex.stringPattern + ".xml")
  private[this] def isXmlUuid(candidate:String) = xmlUuidPattern.matcher(candidate).matches

}