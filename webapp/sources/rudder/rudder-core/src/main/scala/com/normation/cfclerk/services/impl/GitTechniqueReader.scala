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

package com.normation.cfclerk.services.impl

import scala.xml._
import com.normation.cfclerk.domain._
import java.io.FileNotFoundException

import org.xml.sax.SAXParseException
import java.io.File

import net.liftweb.common._

import scala.collection.mutable.{Map => MutMap}
import scala.collection.immutable.SortedMap
import java.io.InputStream

import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.lib.ObjectId

import scala.collection.mutable.{Map => MutMap}
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.services._

import scala.collection.JavaConverters._
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import java.io.IOException

import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

/**
 *
 * A TechniqueReader that reads policy techniques from
 * a git repository.
 *
 * The root directory on the git repos is assumed to be
 * a parent directory of the root directory of the policy
 * template library. For example, if "techniques" is
 * the root directory of the PT lib:
 * - (1) /some/path/techniques/.git [=> OK]
 * - (2) /some/path/
 *             |- techniques
 *             ` .git  [=> OK]
 * - (3) /some/path/
 *             |-techniques
 *             ` sub/dirs/.git [=> NOT OK]
 *
 * The relative path from the parent of .git to ptlib root is given in
 * the "relativePathToGitRepos" parameter.
 *
 * The convention used about policy techniques and categories are the
 * same than for the FSTechniqueReader, which are:
 *
 * - all directories which contains a metadata.xml file is
 *   considered to be a policy package.
 *
 * - template files are looked in the directory
 *
 * - all directory without a metadata.xml are considered to be
 *   a category directory.
 *
 * - if a category directory contains a category.xml file,
 *   information are look from it, else file name is used.
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
 * @param relativePathToGitRepos
 *   The relative path from the root directory of the git repository to
 *   the root directory of the policy template library.
 *   If the root directory of the git repos is in the PT lib root dir,
 *   as in example (1) above, None ("") must be used.
 *   Else, the relative path without leading nor trailing "/" is used. For
 *   example, in example (2), Some("techniques") must be used.
 */

class GitTechniqueReader(
  techniqueParser            : TechniqueParser,
  revisionProvider           : GitRevisionProvider,
  repo                       : GitRepositoryProvider,
  val techniqueDescriptorName: String, //full (with extension) conventional name for policy descriptor
  val categoryDescriptorName : String, //full (with extension) name of the descriptor for categories
  val relativePathToGitRepos : Option[String],
  val directiveDefaultName   : String //full (with extension) name of the file containing default name for directive (default-directive-names.conf)
) extends TechniqueReader with Loggable {

  reader =>

  //denotes a path for a technique, so it starts by a "/"
  //and is not prefixed by relativePathToGitRepos
  private[this] case class TechniquePath(path:String)

  //the path of the PT lib relative to the git repos
  //withtout leading and trailing /.
  val canonizedRelativePath = relativePathToGitRepos.flatMap { path =>
      val p1 = path.trim
      val p2 = if(p1(0) == '/') p1.tail else p1
      val p3 = if(p2(p2.size -1) == '/') p2.substring(0, p2.size-1) else p2

      if(p3.size == 0) { //can not have Some("/") or Some("")
        None
      } else {
        Some(p3)
      }
  }

  /*
   * Change a path relative to the Git repository to a path relative
   * to the root of policy template library.
   * As it is required that the git repository is in  a parent of the
   * ptLib, it's just removing start of the string.
   */
  private[this] def toTechniquePath(path:String) : TechniquePath = {
    canonizedRelativePath match {
      case Some(relative) if(path.startsWith(relative)) =>
        TechniquePath(path.substring(relative.size, path.size))
      case _ => TechniquePath("/" + path)
    }
  }

  //can not set private because of "outer ref cannot be checked" scalac bug
  private case class NoRootCategory(msg: String) extends Exception(msg)

  private[this] var currentTechniquesInfoCache : TechniquesInfo = {
    try {
      processRevTreeId(revisionProvider.currentRevTreeId)
    } catch {
      case NoRootCategory(msg) =>
        logger.error(s"The stored Git revision does not provide a root category.xml, which is mandatory. Error message was: ${msg}")
        val newRevTreeId = revisionProvider.getAvailableRevTreeId
        if(newRevTreeId != revisionProvider.currentRevTreeId) {
          logger.error(s"Trying to load last available revision of the technique library to unstuck the situation.")
          revisionProvider.setCurrentRevTreeId(newRevTreeId)
          processRevTreeId(newRevTreeId)
        } else {
          sys.error("Please add a root category.xml and commit it before restarting Rudder.")
        }
      case e:MissingObjectException => //ah, that commit is not know on our repos
        logger.error("The stored Git revision for the last version of the known Technique Library was not found in the local Git repository. " +
            "That may happen if a commit was reverted, the Git repository was deleted and created again, or if LDAP datas where corrupted. Loading the last available Techique library version.")
        val newRevTreeId = revisionProvider.getAvailableRevTreeId
        revisionProvider.setCurrentRevTreeId(newRevTreeId)
        processRevTreeId(newRevTreeId)
    }
  }

  private[this] var nextTechniquesInfoCache : (ObjectId,TechniquesInfo) = (revisionProvider.currentRevTreeId, currentTechniquesInfoCache)
  //a non empty list IS the indicator of differences between current and next
  private[this] var modifiedTechniquesCache : Map[TechniqueName, TechniquesLibraryUpdateType] = Map()

  override def getModifiedTechniques : Map[TechniqueName, TechniquesLibraryUpdateType] = {
    val nextId = revisionProvider.getAvailableRevTreeId
    if(nextId == nextTechniquesInfoCache._1) modifiedTechniquesCache
    else reader.synchronized { //update next and calculate diffs
      val nextTechniquesInfo = processRevTreeId(nextId)

      //get the list of ALL valid package infos, both in current and in next version,
      //so we have both deleted package (from current) and new one (from next)
      val allKnownTechniquePaths = getTechniquePath(currentTechniquesInfoCache) ++ getTechniquePath(nextTechniquesInfo)

      val diffFmt = new DiffFormatter(null)
      diffFmt.setRepository(repo.db)
      val diffPathEntries : Set[(TechniquePath, ChangeType)] =
        diffFmt.scan(revisionProvider.currentRevTreeId, nextId).asScala.flatMap { diffEntry =>
          Seq( (toTechniquePath(diffEntry.getOldPath), diffEntry.getChangeType), (toTechniquePath(diffEntry.getNewPath), diffEntry.getChangeType))
        }.toSet
      diffFmt.close

      /*
       * now, group diff entries by TechniqueId to find which were updated
       * we take into account any modifications, as anything among a
       * delete, rename, copy, add, modify must be accepted and the matching
       * datetime saved.
       */
      val modifiedTechnique : Set[(TechniqueId, ChangeType)] = diffPathEntries.flatMap { case (path, changeType) =>
        allKnownTechniquePaths.find { case (techniquePath, _) =>
          path.path.startsWith(techniquePath.path)
        }.map { case (n, techniqueId) =>
          (techniqueId, changeType)
        }
      }

      //now, build the actual modification by techniques:
      val techniqueMods = modifiedTechnique.groupBy( _._1.name ).map { case (name, mods) =>
        //deduplicate mods by ids:
        val versionsMods: Map[TechniqueVersion, TechniqueVersionModType] = mods.groupBy( _._1.version).map { case(version, pairs) =>
          val modTypes = pairs.map( _._2 ).toSet

          //merge logic
          //delete + add (+mod) => have to check if still present
          //delete (+ mod) => delete
          //add (+ mod) => add
          //other: mod
          if(modTypes.contains(ChangeType.DELETE)) {
            if(modTypes.contains(ChangeType.ADD)) {
              if(currentTechniquesInfoCache.techniquesCategory.isDefinedAt(TechniqueId(name, version))) {
                //it was present before mod, so can't have been added first, so
                //it was deleted then added (certainly a move), so it is actually mod
                (version, VersionUpdated)
              } else {
                //it was not here, so was added then deleted, so it's a noop.
                //not sure we want to trace that ? As a deletion perhaps ?
                (version, VersionDeleted)
              }
            } else {
              (version, VersionDeleted)
            }
          } else if(modTypes.contains(ChangeType.ADD)) {
            //add
            (version, VersionAdded)
          } else {
            //mod
            (version, VersionUpdated)
          }
        }.toMap

        //now, build the technique mod.
        //distinguish the case where all mod are deletion AND they represent the whole set of known version
        //from other case (just simple updates)

        if(versionsMods.values.forall( _ == VersionDeleted)
           && currentTechniquesInfoCache.techniques.get(name).map( _.size) == Some(versionsMods.size)
        ) {
          (name, TechniqueDeleted(name, versionsMods.keySet))
        } else {
          (name, TechniqueUpdated(name, versionsMods))
        }
      }.toMap

      //Ok, now rebuild Technique !
      modifiedTechniquesCache = techniqueMods
      nextTechniquesInfoCache = (nextId, nextTechniquesInfo)
      modifiedTechniquesCache
    }
  }

  override def getMetadataContent[T](techniqueId: TechniqueId)(useIt : Option[InputStream] => T) : T = {
    //build a treewalk with the path, given by metadata.xml
    val path = techniqueId.toString + "/" + techniqueDescriptorName
    //has package id are unique among the whole tree, we are able to find a
    //template only base on the packageId + name.

    var is : InputStream = null
    try {
      useIt {
        //now, the treeWalk
        val tw = new TreeWalk(repo.db)
        tw.setFilter(new FileTreeFilter(canonizedRelativePath, path))
        tw.setRecursive(true)
        tw.reset(revisionProvider.currentRevTreeId)
        var ids = List.empty[ObjectId]
        while(tw.next) {
          ids = tw.getObjectId(0) :: ids
        }
        ids match {
          case Nil =>
            logger.error("Metadata file %s was not found for technique with id %s.".format(techniqueDescriptorName, techniqueId))
            None
          case h :: Nil =>
            is = repo.db.open(h).openStream
            Some(is)
          case _ =>
            logger.error(s"There is more than one Technique with ID '${techniqueId}', what is forbidden. Please check if several categories have that Technique, and rename or delete the clones.")
            None
      } }
    } catch {
      case ex:FileNotFoundException =>
        logger.debug( () => "Template %s does not exist".format(path),ex)
        useIt(None)
    } finally {
      if(null != is) {
        is.close
      }
    }
  }

  override def getResourceContent[T](techniqueResourceId: TechniqueResourceId, postfixName: Option[String])(useIt : Option[InputStream] => T) : T = {
    //build a treewalk with the path, given by TechniqueTemplateId
    val filenameFilter = {
      val name = techniqueResourceId.name + postfixName.getOrElse("")
      techniqueResourceId match {
        case TechniqueResourceIdByName(tid, _) =>
          new FileTreeFilter(canonizedRelativePath, s"${tid.name}/${tid.version.toString}/${name}")
        case TechniqueResourceIdByPath(Nil, _) =>
          new FileTreeFilter(None, name)
        case TechniqueResourceIdByPath(parents, _) =>
          new FileTreeFilter(Some(parents.mkString("/")), name)
      }
    }

    //has package id are unique among the whole tree, we are able to find a
    //template only base on the techniqueId + name.

    var is : InputStream = null
    try {
      useIt {
        //now, the treeWalk
        val tw = new TreeWalk(repo.db)
        tw.setFilter(filenameFilter)
        tw.setRecursive(true)
        tw.reset(revisionProvider.currentRevTreeId)
        var ids = List.empty[ObjectId]
        while(tw.next) {
          ids = tw.getObjectId(0) :: ids
        }
        ids match {
          case Nil =>
            logger.error(s"Template with id ${techniqueResourceId.toString} was not found")
            None
          case h :: Nil =>
            is = repo.db.open(h).openStream
            Some(is)
          case _ =>
            logger.error(s"There is more than one Technique with name '${techniqueResourceId.name}' which is forbidden. Please check if several categories have that Technique and rename or delete the clones")
            None
      } }
    } catch {
      case ex:FileNotFoundException =>
        logger.debug( () => s"Technique Template ${techniqueResourceId.toString} does not exist", ex)
        useIt(None)
    } finally {
      if(null != is) {
        is.close
      }
    }
  }

  /**
   * Read the policies from the last available tag.
   * The last available tag state is given by modifiedTechniques
   * and is ONLY updated by that method.
   * Two subsequent call to readTechniques without a call
   * to modifiedTechniques does nothing, even if some
   * commit were done in git repository.
   */
  override def readTechniques : TechniquesInfo = {
    reader.synchronized {
      if(needReload) {
        currentTechniquesInfoCache = nextTechniquesInfoCache._2
        revisionProvider.setCurrentRevTreeId(nextTechniquesInfoCache._1)
        modifiedTechniquesCache = Map()
      }
      currentTechniquesInfoCache
    }
  }


  override def needReload() = revisionProvider.currentRevTreeId != nextTechniquesInfoCache._1

  private[this] def processRevTreeId(id:ObjectId, parseDescriptor:Boolean = true) : TechniquesInfo = {
    /*
     * Global process : the logic is completly different
     * from a standard "directory then subdirectoies" walk, because
     * we have access to the full list of path in that RevTree.
     * So, we are just looking for:
     * - paths which end by categoryDescriptorName:
     *   these paths parents are category path if and only
     *   if their own parent is a category
     * - paths which end by techniqueDescriptorName
     *   these paths are policy version directories if and only
     *   if:
     *   - their direct parent name is a valid version number
     *   - their direct great-parent is a valid category
     *
     * As the tree walk, we are sure that:
     * - A/B/cat.xml < A/B/C/cat.xml (and so we can say that the second is valid if the first is)
     * - A/B/cat.xml < A/B/0.1/pol.xml (and so we can say that the second is an error);
     * - A/B/cat.xml < A/B/P/0.1/pol.xml (and so we can say that the second is valid if the first is)
     *
     * We know if the first is valid because:
     * - we are always looking for a category
     * - and so we have to found the matching catId in the category map.
     */
      val techniqueInfos = new InternalTechniquesInfo()
      //we only want path ending by a descriptor file

      //start to process all categories related information
      processCategories(id,techniqueInfos, parseDescriptor)

      //now, build techniques
      processTechniques(id,techniqueInfos,parseDescriptor)

      //ok, return the result in its immutable format
      TechniquesInfo(
        rootCategory = techniqueInfos.rootCategory.get,
        gitRevId = id.name(),
        techniquesCategory = techniqueInfos.techniquesCategory.toMap,
        techniques = techniqueInfos.techniques.map { case(k,v) => (k, SortedMap.empty[TechniqueVersion,Technique] ++ v)}.toMap,
        subCategories = Map[SubTechniqueCategoryId, SubTechniqueCategory]() ++ techniqueInfos.subCategories,
        processDirectiveDefaultName(id)
      )
  }

  private[this] def processDirectiveDefaultName(revTreeId: ObjectId) : Map[String, String] = {
      //a first walk to find categories
      val tw = new TreeWalk(repo.db)
      tw.setFilter(new FileTreeFilter(canonizedRelativePath, directiveDefaultName))
      tw.setRecursive(true)
      tw.reset(revTreeId)

      val prop = new java.util.Properties()

      //now, for each potential path, look if the cat or policy
      //is valid
      while(tw.next) {
        //we need to filter out directories
        if(tw.getNameString == directiveDefaultName) {
          var is : InputStream = null
          try {
            is = repo.db.open(tw.getObjectId(0)).openStream
            prop.load(is)
          } catch {
            case ex: Exception =>
              logger.error(s"Error when trying to load directive default name from '${directiveDefaultName}' No specific default naming rules will be available. ", ex)
              Map()
          } finally {
            try {
              if (is != null) { is.close() }
            } catch {
              case ioe: IOException => // ignore
            }
          }
        }
      }
      import scala.collection.JavaConverters._
      prop.asScala.toMap

  }

  private[this] def processTechniques(revTreeId: ObjectId, techniqueInfos : InternalTechniquesInfo, parseDescriptor:Boolean) : Unit = {
      //a first walk to find categories
      val tw = new TreeWalk(repo.db)
      tw.setFilter(new FileTreeFilter(canonizedRelativePath, techniqueDescriptorName))
      tw.setRecursive(true)
      tw.reset(revTreeId)

      //now, for each potential path, look if the cat or policy
      //is valid
      while(tw.next) {
        val path = toTechniquePath(tw.getPathString) //we will need it to build the category id
        processTechnique(repo.db.open(tw.getObjectId(0)).openStream, path.path, techniqueInfos, parseDescriptor, revTreeId)
      }
  }


  private[this] def processCategories(revTreeId: ObjectId, techniqueInfos : InternalTechniquesInfo, parseDescriptor:Boolean) : Unit = {
      //a first walk to find categories
      val tw = new TreeWalk(repo.db)
      tw.setFilter(new FileTreeFilter(canonizedRelativePath, categoryDescriptorName))
      tw.setRecursive(true)
      tw.reset(revTreeId)


      val maybeCategories = MutMap[TechniqueCategoryId, TechniqueCategory]()

      //now, for each potential path, look if the cat or policy
      //is valid
      while(tw.next) {
        val path = toTechniquePath(tw.getPathString) //we will need it to build the category id
        registerMaybeCategory(tw.getObjectId(0), path.path, maybeCategories, parseDescriptor)
      }

      val toRemove = new collection.mutable.HashSet[SubTechniqueCategoryId]()
      maybeCategories.foreach {
        case (sId:SubTechniqueCategoryId,cat:SubTechniqueCategory) =>
          recToRemove(sId,toRemove, maybeCategories)

        case _ => //ignore
      }

      //now, actually remove things
      maybeCategories --= toRemove

      //update techniqueInfos
      techniqueInfos.subCategories ++= maybeCategories.collect { case (sId:SubTechniqueCategoryId, cat:SubTechniqueCategory) => (sId -> cat) }


      var root = maybeCategories.get(RootTechniqueCategoryId) match {
          case None =>
            val path = repo.db.getWorkTree.getPath + canonizedRelativePath.map( "/" + _ + "/" + categoryDescriptorName).getOrElse("")
            throw new NoRootCategory(s"Missing techniques root category in Git, expecting category descriptor for Git path: '${path}'")
          case Some(sub:SubTechniqueCategory) =>
            logger.error("Bad type for root category in the Technique Library. Please check the hierarchy of categories")
            throw new NoRootCategory(s"Bad type for root category in the Technique Library, found: '${sub}'. Please check the hierarchy of categories")
          case Some(r:RootTechniqueCategory) => r
      }

      //update subcategories
      techniqueInfos.subCategories.toSeq.foreach {
        case(sId@SubTechniqueCategoryId(_,RootTechniqueCategoryId) , _ ) => //update root
          root = root.copy( subCategoryIds = root.subCategoryIds + sId )
        case(sId@SubTechniqueCategoryId(_,pId:SubTechniqueCategoryId) , _ ) =>
          val cat = techniqueInfos.subCategories(pId)
          techniqueInfos.subCategories(pId) = cat.copy( subCategoryIds = cat.subCategoryIds + sId )
      }

      //finally, update root !
      techniqueInfos.rootCategory = Some(root)

  }


  /**
   * We remove each category for which parent category is not defined.
   */
  private[this] def recToRemove(
      catId:SubTechniqueCategoryId
    , toRemove:collection.mutable.HashSet[SubTechniqueCategoryId]
    , maybeCategories: MutMap[TechniqueCategoryId, TechniqueCategory]
  ) : Boolean = {
      catId.parentId match {
        case RootTechniqueCategoryId => false
        case sId:SubTechniqueCategoryId =>
          if(toRemove.contains(sId)) {
            toRemove += catId
            true
          } else if(maybeCategories.isDefinedAt(sId)) {
            recToRemove(sId, toRemove, maybeCategories )
          } else {
            toRemove += catId
            true
          }
      }
  }

  private[this] val dummyTechnique = Technique(
      TechniqueId(TechniqueName("dummy"),TechniqueVersion("1.0"))
    , "dummy", "dummy", Nil, TrackerVariableSpec()
    , SectionSpec("ROOT"), None
 )

  private[this] def processTechnique(
      is:InputStream
    , filePath:String
    , techniquesInfo:InternalTechniquesInfo
    , parseDescriptor:Boolean // that option is a success optimization for the case diff between old/new commit
    , revTreeId: ObjectId
  ): IO[RudderError, Unit] = {
    def updateParentCat(catId: TechniqueCategoryId, techniqueId: TechniqueId, descriptorFile: File) : Boolean = {
      catId match {
        case RootTechniqueCategoryId =>
          val cat = techniquesInfo.rootCategory.getOrElse(
              throw new RuntimeException("Can not find the parent (root) category %s for package %s".format(descriptorFile.getParent, TechniqueId))
          )
          techniquesInfo.rootCategory = Some(cat.copy(techniqueIds = cat.techniqueIds + techniqueId ))
          true

        case sid:SubTechniqueCategoryId =>
          techniquesInfo.subCategories.get(sid) match {
            case Some(cat) =>
              techniquesInfo.subCategories(sid) = cat.copy(techniqueIds = cat.techniqueIds + techniqueId )
              true
            case None =>
              logger.error("Can not find the parent category %s for package %s".format(descriptorFile.getParent, TechniqueId))
              false
          }
      }
    }

    val descriptorFile = new File(filePath)
    val policyVersion = TechniqueVersion(descriptorFile.getParentFile.getName)
    val policyName = TechniqueName(descriptorFile.getParentFile.getParentFile.getName)
    val parentCategoryId = TechniqueCategoryId.buildId(descriptorFile.getParentFile.getParentFile.getParent )
    val techniqueId = TechniqueId(policyName,policyVersion)

    for {
      pack <- if(parseDescriptor) loadDescriptorFile(is, filePath).flatMap(d => ZIO.fromEither(techniqueParser.parseXml(d, techniqueId)))
                 else dummyTechnique.succeed
      res  <- Task.effect {
                //check that that package is not already know, else its an error (by id ?)
                techniquesInfo.techniques.get(techniqueId.name) match {
                  case None => //so we don't have any version yet, and so no id
                    if(updateParentCat(parentCategoryId, techniqueId, descriptorFile)) {
                      techniquesInfo.techniques(techniqueId.name) = MutMap(techniqueId.version -> pack)
                      techniquesInfo.techniquesCategory(techniqueId) = parentCategoryId
                    }
                  case Some(versionMap) => //check for the version
                    versionMap.get(techniqueId.version) match {
                      case None => //add that version
                        if(updateParentCat(parentCategoryId, techniqueId, descriptorFile)) {
                          techniquesInfo.techniques(techniqueId.name)(techniqueId.version) = pack
                          techniquesInfo.techniquesCategory(techniqueId) = parentCategoryId
                        }
                      case Some(v) => //error, policy package version already exsits
                        logger.error("Ignoring package for policy with ID %s and root directory %s because an other policy is already defined with that id and root path %s".format(
                            TechniqueId, descriptorFile.getParent, techniquesInfo.techniquesCategory(techniqueId).toString)
                        )
                    }
                }
              } fold ( err => err match {
                case e : TechniqueVersionFormatException => s"Ignoring technique '${filePath}}' because the version format is incorrect. Error message was: ${e.getMessage}}".fail
                case e : ConstraintException => s"Ignoring technique '${filePath}}' because the descriptor file is malformed. Error message was: ${e.getMessage}}".fail
                case e : Exception => s"Error when processing technique '${filePath}}': ${e.getMessage}}".fail
              }
              , ok => ok.succeed
              )
    }yield {
      res
    }
  }

  /**
   * Register a category, but whithout checking that its parent
   * is legal.
   * So that will lead to an unconsistant Map of categories
   * which must be normalized before use !
   *
   * If the category descriptor is here, but incorrect,
   * we assume that the directory should be considered
   * as a category, but the user did a mistake: signal it,
   * but DO use the folder as a category.
   */
  private[this] def registerMaybeCategory(
      descriptorObjectId: ObjectId
    , filePath          : String
    , maybeCategories   : MutMap[TechniqueCategoryId, TechniqueCategory]
    , parseDescriptor   : Boolean // that option is a success optimization for the case diff between old/new commit
  ) : IO[RudderError, Unit] = {

    def nonEmpty(s: String): Option[String] = {
      s match {
        case null | "" => None
        case _ => Some(s)
      }
    }
    def parse(parseDesc:Boolean, catId: TechniqueCategoryId): IO[RudderError, (String, String, Boolean)] = {
      if(parseDesc) {
        loadDescriptorFile(repo.db.open(descriptorObjectId).openStream, filePath).map { xml =>
          val name = nonEmpty((xml \\ "name").text).getOrElse(catId.name.value)
          val description = nonEmpty((xml \\ "description").text).getOrElse("")
          val isSystem = (nonEmpty((xml \\ "system").text).getOrElse("false")).equalsIgnoreCase("true")
          (name, description, isSystem)
        }
      } else {
        (catId.name.value, "", false).succeed
      }
    }

    val catPath = filePath.substring(0, filePath.size - categoryDescriptorName.size - 1 ) // -1 for the trailing slash

    val catId = TechniqueCategoryId.buildId(catPath)
    //built the category
    for {
      triple <- parse(parseDescriptor, catId)
    } yield {
      val (name, desc, system ) = triple
      val cat = catId match {
        case RootTechniqueCategoryId => RootTechniqueCategory(name, desc, isSystem = system)
        case sId:SubTechniqueCategoryId => SubTechniqueCategory(sId, name, desc, isSystem = system)
      }
      maybeCategories(cat.id) = cat
    }
  }

  /**
   * Load a descriptor document.
   * @param file : the full filename
   * @return the xml representation of the file
   */
  private[this] def loadDescriptorFile(is: InputStream, filePath : String ) : IO[RudderError, Elem] = {
    Task.effect {
      XML.load(is)
    }.foldM(
      err => err match {
        case e: SAXParseException =>
          LoadTechniqueError.Parsing(s"Unexpected issue with the descriptor file '${filePath}' at line ${e.getLineNumber}, column ${e.getColumnNumber}: ${e.getMessage}").fail
        case e: java.net.MalformedURLException =>
          LoadTechniqueError.Parsing("Descriptor file not found: " + filePath).fail
        case ex => SystemError(s"Error when parsing ${filePath}", ex).fail
      }
    , doc => if(doc.isEmpty) {
        LoadTechniqueError.Parsing(s"Error when parsing descriptor file: '${filePath}': the parsed document is empty").fail
      } else doc.succeed
    )
  }

  /**
   * Output the set of path for all techniques.
   * Root is "/", so that a package "P1" is denoted
   * /P1, a package P2 in sub category cat1 is denoted
   * /cat1/P2, etc.
   * As we may have files&templates outside technique, we
   * need to keep track of there relative id
   */
  private[this] def getTechniquePath(techniqueInfos:TechniquesInfo) : Set[(TechniquePath, TechniqueId)] = {
    val set = scala.collection.mutable.Set[(TechniquePath, TechniqueId)]()
    techniqueInfos.rootCategory.techniqueIds.foreach { id =>
      set += ((TechniquePath( "/" + id.toString), id))
    }
    techniqueInfos.subCategories.foreach { case (id,cat) =>
      val path = id.toString
      cat.techniqueIds.foreach { t => set += ((TechniquePath(path + "/" + t.toString), t)) }
    }
    //also add template "by path"
    val techniques = techniqueInfos.techniques.flatMap { case(_, set) => set.map { case(_, t) => t } }
    techniques.foreach { t =>
      val byPath = t.agentConfigs.flatMap(cfg => cfg.templates.collect { case TechniqueTemplate(id@TechniqueResourceIdByPath(_,_),_,_) => id }) ++
                   t.agentConfigs.flatMap(cfg => cfg.files.collect { case TechniqueFile(id@TechniqueResourceIdByPath(_,_),_,_) => id })
      byPath.foreach { resource =>
        //here, "/" is needed at the begining because diffEntry have one, so if we don't
        //add it, we won't find is back in modifiedTechnique and diffPathEntries
        set += ((TechniquePath(resource.parentDirectories.mkString("/", "/", "/") + resource.name), t.id))
      }
    }
    set.toSet
   }
}
