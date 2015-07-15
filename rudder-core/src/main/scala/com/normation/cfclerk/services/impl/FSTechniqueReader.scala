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

import scala.xml._
import com.normation.cfclerk.domain._
import java.io.FileNotFoundException
import org.xml.sax.SAXParseException
import com.normation.cfclerk.exceptions._
import org.slf4j.{ Logger, LoggerFactory }
import java.io.File
import org.apache.commons.io.FilenameUtils
import com.normation.cfclerk.xmlparsers.TechniqueParser
import net.liftweb.common._
import scala.collection.mutable.{ Map => MutMap }
import scala.collection.SortedSet
import com.normation.utils.Utils
import scala.collection.immutable.SortedMap
import java.io.InputStream
import java.io.FileInputStream
import com.normation.cfclerk.services._

/**
 *
 * A TechniqueReader that reads policy techniques from
 * a directory tree.
 *
 *
 * Conventions used:
 *
 * - all directories which contains a metadata.xml file is
 *   considered to be a policy package.
 *
 * - template files are looked in the directory
 *
 * - a category directory contains a category.xml file, and
 *   information are look from it, else file name is used.
 *
 * - directory without metadata.xml or category.xml are ignored
 *
 *  Category description information are stored in XML files with the expected
 *  structure:
 *  <xml>
 *    <name>Name of the category</name>
 *    <description>Description of the category</description>
 *  </xml>
 *
 *  In that implementation, the name of the directory of a category
 *  is used for the techniqueCategoryName.
 *
 */
class FSTechniqueReader(
  policyParser               : TechniqueParser,
  val techniqueDirectoryPath : String,
  val techniqueDescriptorName: String, //full (with extension) conventional name for policy descriptor
  val categoryDescriptorName : String, //full (with extension) name of the descriptor for categories
  val reportingDescriptorName: String  //the name of the file for expected_report.csv. It must be only the name, not the path.
  ) extends TechniqueReader with Loggable {

  reader =>

  private def checkTechniqueDirectory(dir: File): Unit = {
    if (!dir.exists) {
      throw new RuntimeException("Directory %s does not exists, how do you want that I read a technique from it?".format(dir))
    }
    if (!dir.canRead) {
      throw new RuntimeException("Directory %s is not readable, how do you want that I read a technique from it?".format(dir))
    }
  }

  private[this] val techniqueDirectory: File = {
    val dir = new File(techniqueDirectoryPath)
    checkTechniqueDirectory(dir)
    dir
  }

  //we never know when to read again
  override def getModifiedTechniques : Map[TechniqueName, TechniquesLibraryUpdateType] = Map()

  /**
   * Read the policies
   * @param doc : the xml representation of the knowledge file
   * @return a map of policy
   */
  override lazy val readTechniques: TechniquesInfo = {
    reader.synchronized {
      val techniqueInfos = new InternalTechniquesInfo()

      processDirectory(
        null, //we have to ignore it, so if we don't, a NPE is a good idea.
        reader.techniqueDirectory,
        techniqueInfos)

      TechniquesInfo(
        rootCategory = techniqueInfos.rootCategory.getOrElse(throw new RuntimeException("No root category was found")),
        techniquesCategory = techniqueInfos.techniquesCategory.toMap,
        techniques = techniqueInfos.techniques.map { case (k, v) => (k, SortedMap.empty[TechniqueVersion, Technique] ++ v) }.toMap,
        subCategories = techniqueInfos.subCategories.toMap)
    }
  }

  override def getMetadataContent[T](techniqueId: TechniqueId)(useIt: Option[InputStream] => T): T = {
    var is: InputStream = null
    try {
      useIt {
        readTechniques.techniquesCategory.get(techniqueId).map { catPath =>
          is = new FileInputStream(techniqueDirectory.getAbsolutePath + "/" + catPath + "/" + techniqueDescriptorName)
          is
        }
      }
    } catch {
      case ex: FileNotFoundException =>
        logger.debug(() => "Can not find %s for Technique with ID %s".format(techniqueDescriptorName, techniqueId), ex)
        useIt(None)
    } finally {
      if (null != is) {
        is.close
      }
    }
  }

  override def checkreportingDescriptorExistence(techniqueId: TechniqueId) : Boolean = {
    readTechniques.techniquesCategory.get(techniqueId).map { catPath =>
      (new File(techniqueDirectory.getAbsolutePath + "/" + catPath + "/" + reportingDescriptorName)).exists
    }.getOrElse(false)
  }


  override def getReportingDetailsContent[T](techniqueId: TechniqueId)(useIt : Option[InputStream] => T) : T = {
    var is: InputStream = null
    try {
      useIt {
        readTechniques.techniquesCategory.get(techniqueId).map { catPath =>
          is = new FileInputStream(techniqueDirectory.getAbsolutePath + "/" + catPath + "/" + reportingDescriptorName)
          is
        }
      }
    } catch {
      case ex: FileNotFoundException =>
        logger.debug(() => "Can not find %s for Technique with ID %s".format(reportingDescriptorName, techniqueId), ex)
        useIt(None)
    } finally {
      if (null != is) {
        is.close
      }
    }
  }
  override def getTemplateContent[T](templateId: Cf3PromisesFileTemplateId)(useIt: Option[InputStream] => T): T = {
    var is: InputStream = null
    try {
      useIt {
        readTechniques.techniquesCategory.get(templateId.techniqueId).map { catPath =>
          is = new FileInputStream(techniqueDirectory.getAbsolutePath + "/" + catPath + "/" + templateId.toString + Cf3PromisesFileTemplate.templateExtension)
          is
        }
      }
    } catch {
      case ex: FileNotFoundException =>
        logger.debug(() => "Template %s does not exists".format(templateId), ex)
        useIt(None)
    } finally {
      if (null != is) {
        is.close
      }
    }
  }

  /**
   * Process the directory to find if it's a category
   * or a package.
   * f must be a directory
   */
  //  private def processDirectory(f: File, internalTechniquesInfo: InternalTechniquesInfo): Unit = {
  //    val children = f.listFiles
  //    val childrenName = children.map(_.getName)
  //    (childrenName.exists(c => c == techniqueDescriptorName), childrenName.exists(c => c == categoryDescriptorName)) match {
  //      case (true, true) =>
  //        throw new RuntimeException("Directory %s contains both file %s and %s: can not know if it is a category or a policy package".
  //          format(f.getAbsolutePath, techniqueDescriptorName, categoryDescriptorName))
  //      case (true, false) => processTechnique(f, internalTechniquesInfo)
  //      case (false, _) => processCategory(f, internalTechniquesInfo)
  //    }
  //  }

  private def processDirectory(parentCategoryId: TechniqueCategoryId, f: File, internalTechniquesInfo: InternalTechniquesInfo): Unit = {
    val children = f.listFiles
    val childrenName = children.map(_.getName)
    val versionsDir = children.filter(_.isDirectory).flatMap(_.listFiles).filter(f => f.getName == techniqueDescriptorName).map(_.getParentFile)

    if (versionsDir.size > 0) {
      for (d <- versionsDir) {
        processTechnique(parentCategoryId, d, internalTechniquesInfo)
      }
    } else {
      processCategory(parentCategoryId, f, internalTechniquesInfo)
    }

  }

  /**
   * Load a Technique contains in the directory packageRootDirectory.
   * By convention, packageRootDirectory is the version of the Technique,
   * and the parent's name directory is the Technique Name.
   */
  private def processTechnique(parentCategoryId: TechniqueCategoryId, packageRootDirectory: File, internalTechniquesInfo: InternalTechniquesInfo): Unit = {
    val name = TechniqueName(packageRootDirectory.getParentFile.getName)
    val id = TechniqueId(name, TechniqueVersion(packageRootDirectory.getName))

    val descriptorFile = new File(packageRootDirectory, techniqueDescriptorName)
    //check if the expected_report.csv file exists
    val expectedReportCsv = new File(descriptorFile.getParentFile, reportingDescriptorName)

    val pack = policyParser.parseXml(loadDescriptorFile(descriptorFile), id, expectedReportCsv.exists)

      def updateParentCat() {
        parentCategoryId match {
          case RootTechniqueCategoryId =>
            val cat = internalTechniquesInfo.rootCategory.getOrElse(
              throw new RuntimeException("Can not find the parent (root) category %s for technique %s".format(packageRootDirectory.getParentFile.getAbsolutePath, pack.id)))
            internalTechniquesInfo.rootCategory = Some(cat.copy(packageIds = cat.packageIds + pack.id))

          case sid: SubTechniqueCategoryId =>
            val cat = internalTechniquesInfo.subCategories.get(sid).getOrElse(
              throw new RuntimeException("Can not find the parent category %s for technique %s".format(packageRootDirectory.getParentFile.getAbsolutePath, pack.id)))
            internalTechniquesInfo.subCategories(sid) = cat.copy(packageIds = cat.packageIds + pack.id)

        }
      }

    //check that that package is not already know, else its an error (by id ?)
    internalTechniquesInfo.techniques.get(pack.id.name) match {
      case None => //so we don't have any version yet, and so no id
        updateParentCat()
        internalTechniquesInfo.techniques(pack.id.name) = MutMap(pack.id.version -> pack)
        internalTechniquesInfo.techniquesCategory(pack.id) = parentCategoryId
      case Some(versionMap) => //check for the version
        versionMap.get(pack.id.version) match {
          case None => //add that version
            updateParentCat()
            internalTechniquesInfo.techniques(pack.id.name)(pack.id.version) = pack
            internalTechniquesInfo.techniquesCategory(pack.id) = parentCategoryId
          case Some(v) => //error, policy package version already exsits
            logger.error("Ignoring package for policy with ID %s and root directory %s because an other policy is already defined with that id and root path %s".format(
              pack.id, packageRootDirectory.getAbsolutePath, internalTechniquesInfo.techniquesCategory(pack.id).toString))
        }
    }

  }

  /**
   * Process the categoryRootDirectory which must be a category directory.
   * - if the file categoryDescriptorName exists, use it to find name, description,
   *   else use file name
   * - process all sub-directories
   * - ignore all other files
   *
   * @param packageRootDirectory
   * @param internalTechniquesInfo
   */
  private def processCategory(parentCategoryId: TechniqueCategoryId, categoryRootDirectory: File, internalTechniquesInfo: InternalTechniquesInfo): Unit = {

    //build a category without children from file name and path
    //    def categoryFromFile(f: File)(name: String = f.getName, desc: String = "", system: Boolean = false) = TechniqueCategory(
    //      id = parentCategoryId / f.getName, name = name, description = desc, subCategoryIds = SortedSet(), packageIds = SortedSet(), isSystem = system)

    val categoryDescriptor = new File(categoryRootDirectory, categoryDescriptorName)

    if (categoryDescriptor.exists && categoryDescriptor.isFile && categoryDescriptor.canRead) {
      logger.debug("Reading package category information from %s".format(categoryDescriptor.getAbsolutePath))

      try {
        val xml = loadDescriptorFile(categoryDescriptor)
        val name = Utils.??!((xml \\ "name").text).getOrElse(categoryRootDirectory.getName)
        val desc = Utils.??!((xml \\ "description").text).getOrElse("")
        val system = (Utils.??!((xml \\ "system").text).getOrElse("false")).equalsIgnoreCase("true")

        //add the category as a child to its parent (if not root) and as new category
        val newParentId = {
          if (null == parentCategoryId) {
            internalTechniquesInfo.rootCategory = Some(RootTechniqueCategory(name, desc, isSystem = system))
            RootTechniqueCategoryId
          } else {
            val category = SubTechniqueCategory(
              id = parentCategoryId / categoryRootDirectory.getName, name = name, description = desc, isSystem = system)
            parentCategoryId match {
              case RootTechniqueCategoryId =>
                val parent = internalTechniquesInfo.rootCategory.getOrElse(throw new RuntimeException("Missing root technique category"))
                internalTechniquesInfo.rootCategory = Some(parent.copy(subCategoryIds = parent.subCategoryIds + category.id))
              case sid: SubTechniqueCategoryId =>
                val parent = internalTechniquesInfo.subCategories(sid)
                internalTechniquesInfo.subCategories(parent.id) = parent.copy(subCategoryIds = parent.subCategoryIds + category.id)
            }

            internalTechniquesInfo.subCategories(category.id) = category
            category.id
          }
        }

        //process sub-directories
        categoryRootDirectory.listFiles.foreach { f =>
          if (f.isDirectory) {
            processDirectory(newParentId, f, internalTechniquesInfo)
          }
        }

      } catch {
        case e: Exception =>
          logger.error("Error when processing category descriptor %s, ignoring path".format(categoryDescriptor.getAbsolutePath))
      }
    } else {
      logger.info("No package category descriptor '%s' for directory '%s', ignoring the path".format(categoryDescriptorName, categoryRootDirectory.getAbsolutePath))
    }
  }

  /**
   * Load a descriptor document.
   * @param file : the full filename
   * @return the xml representation of the file
   */
  private def loadDescriptorFile(file: File): Elem = {
    val doc =
      try {
        XML.loadFile(file)
      } catch {
        case e: SAXParseException =>
          throw new ParsingException("Unexpected issue with the descriptor file %s: %s".format(file,e.getMessage))
        case e: java.net.MalformedURLException =>
          throw new ParsingException("Descriptor file not found: " + file)
      }

    if (doc.isEmpty) {
      throw new ParsingException("Error when parsing descriptor file: '%s': the parsed document is empty".format(file))
    }

    doc
  }
}
