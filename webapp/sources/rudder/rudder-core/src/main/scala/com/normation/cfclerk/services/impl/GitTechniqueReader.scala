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

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters._
import java.io.InputStream
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.lib.ObjectId

import scala.collection.mutable.{Map => MutMap}
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.services._

import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.diff.DiffEntry.ChangeType

import java.io.IOException

import com.normation.errors._
import com.normation.zio._
import zio._
import zio.syntax._
import GitTechniqueReader._
import com.normation.GitVersion
import com.normation.rudder.domain.logger.TechniqueReaderLoggerPure
import com.normation.rudder.git.ExactFileTreeFilter
import com.normation.rudder.git.GitFindUtils
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.git.GitRevisionProvider

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.ObjectStream


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
object GitTechniqueReader {

  //denotes a path for a technique, so it starts by a "/"
  //and is not prefixed by relativePathToGitRepos
  final case class TechniquePath(path:String) extends AnyVal
}

class GitTechniqueReader(
    techniqueParser            : TechniqueParser
  , revisionProvider           : GitRevisionProvider
  , repo                       : GitRepositoryProvider
  , val techniqueDescriptorName: String //full (with extension) conventional name for policy descriptor
  , val categoryDescriptorName : String //full (with extension) name of the descriptor for categories
  , val relativePathToGitRepos : Option[String]
  , val directiveDefaultName   : String //full (with extension) name of the file containing default name for directive (default-directive-names.conf)
) extends TechniqueReader with Loggable {

  // semaphore to have consistent read
  val semaphore = Semaphore.make(1).runNow

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

  private[this] val currentTechniquesInfoCache: Ref[TechniquesInfo] = semaphore.withPermit(
    for {
      currentRevTree <- revisionProvider.currentRevTreeId
      res            <- processRevTreeId(repo.db, currentRevTree).catchSome {
                          case err @ SystemError(m, NoRootCategory(msg)) =>
                            for {
                              _            <- TechniqueReaderLoggerPure.error(s"The stored Git revision '${currentRevTree.toString}' does not provide a root category.xml, which is mandatory. Error message was: ${err.fullMsg}")
                              newRevTreeId <- revisionProvider.getAvailableRevTreeId
                              res <- if(newRevTreeId != currentRevTree) {
                                        TechniqueReaderLoggerPure.error(s"Trying to load last available revision of the technique library to unstuck the situation.") *>
                                        revisionProvider.setCurrentRevTreeId(newRevTreeId) *>
                                        processRevTreeId(repo.db, newRevTreeId)
                                      } else {
                                        Inconsistency("Please add a root category.xml and commit it before restarting Rudder.").fail
                                      }
                            } yield res
                          case SystemError(m, ex:MissingObjectException) => //ah, that commit is not know on our repos
                            TechniqueReaderLoggerPure.error("The stored Git revision for the last version of the known Technique Library was not found in the local Git repository. " +
                                "That may happen if a commit was reverted, the Git repository was deleted and created again, or if LDAP datas where corrupted. Loading the last available Techique library version.") *>
                            revisionProvider.getAvailableRevTreeId.flatMap(newRevTreeId =>
                              revisionProvider.setCurrentRevTreeId(newRevTreeId) *>
                              processRevTreeId(repo.db, newRevTreeId)
                            )
                        }
    } yield res
  ).flatMap(Ref.make(_)).runNow

  private[this] val nextTechniquesInfoCache: Ref[(ObjectId,TechniquesInfo)] = {
    (for {
      a <- revisionProvider.currentRevTreeId
      b <- currentTechniquesInfoCache.get
      r <- Ref.make((a, b))
    } yield r).runNow
  }

  //a non empty list IS the indicator of differences between current and next
  private[this] val modifiedTechniquesCache: Ref[Map[TechniqueName, TechniquesLibraryUpdateType]] = Ref.make(Map[TechniqueName, TechniquesLibraryUpdateType]()).runNow

  override def getModifiedTechniques : Map[TechniqueName, TechniquesLibraryUpdateType] = {

    def buildTechniqueMods(
        diffPathEntries      : Set[(TechniquePath, ChangeType)]
      , currentTechniquesInfo: TechniquesInfo
      , nextTechniquesInfo   : TechniquesInfo
    ): Map[TechniqueName, TechniquesLibraryUpdateType] = {
      //get the list of ALL valid package infos, both in current and in next version,
      //so we have both deleted package (from current) and new one (from next)
      val allKnownTechniquePaths = getTechniquePath(currentTechniquesInfo) ++ getTechniquePath(nextTechniquesInfo)

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
          // a change in the "resources" sub-directory is always an update
          val change = if(path.path.contains("/resources/")) {
            ChangeType.MODIFY
          } else {
            changeType
          }
          (techniqueId, change)
        }
      }

      //now, build the actual modification by techniques:
      modifiedTechnique.groupBy( _._1.name ).map { case (name, mods) =>
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
              if(currentTechniquesInfo.techniquesCategory.isDefinedAt(TechniqueId(name, version))) {
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
           && currentTechniquesInfo.techniques.get(name).map( _.size) == Some(versionsMods.size)
        ) {
          (name, TechniqueDeleted(name, versionsMods.keySet))
        } else {
          (name, TechniqueUpdated(name, versionsMods))
        }
      }.toMap
    }

    (for {
      nextId   <- revisionProvider.getAvailableRevTreeId
      cached   <- nextTechniquesInfoCache.get
      mods     <- if(nextId == cached._1) modifiedTechniquesCache.get
                  else for {
                    nextTechniquesInfo <- processRevTreeId(repo.db, nextId)
                    managedDiffFmt     =  ZManaged.make(IOResult.effect{
                                            val diffFmt = new DiffFormatter(null)
                                            diffFmt.setRepository(repo.db)
                                            diffFmt
                                          })(diffFmt => effectUioUnit(diffFmt.close))
                    diffPathEntries    <- managedDiffFmt.use(diffFmt => IOResult.effect {
                                            diffFmt.scan(cached._1, nextId).asScala.flatMap { diffEntry =>
                                              Seq( (toTechniquePath(diffEntry.getOldPath), diffEntry.getChangeType), (toTechniquePath(diffEntry.getNewPath), diffEntry.getChangeType))
                                            }.toSet
                                          })
                    next               <- processRevTreeId(repo.db, nextId)
                    mods               =  buildTechniqueMods(diffPathEntries, cached._2, next)
                    nextCache          =  (nextId, next)
                    _                  <- semaphore.withPermit(
                                            modifiedTechniquesCache.set(mods) *> nextTechniquesInfoCache.set(nextCache)
                                          )
                  } yield mods
    } yield {
      mods
    }).runNow
  }

  override def getMetadataContent[T](techniqueId: TechniqueId)(useIt : Option[InputStream] => IOResult[T]) : IOResult[T] = {
    //build a treewalk with the path, given by metadata.xml
    val path = techniqueId.withDefaultRev.serialize + "/" + techniqueDescriptorName
    //has package id are unique among the whole tree, we are able to find a
    //template only base on the packageId + name.

    val managed = Managed.make(
      for {
        currentId <- techniqueId.version.rev match {
                       case GitVersion.DEFAULT_REV => revisionProvider.currentRevTreeId
                       case r                      => GitFindUtils.findRevTreeFromRevString(repo.db, r.value)
                     }
        optStream <- IOResult.effect {
                       try {
                         val tw = new TreeWalk(repo.db)
                         tw.setFilter(new ExactFileTreeFilter(canonizedRelativePath, path))
                         tw.setRecursive(true)
                         tw.reset(currentId)
                         var ids = List.empty[ObjectId]
                         while(tw.next) {
                           ids = tw.getObjectId(0) :: ids
                         }
                         ids match {
                           case Nil =>
                             logger.error(s"Metadata file ${techniqueDescriptorName} was not found for technique with id ${techniqueId.debugString}.")
                             Option.empty[ObjectStream]
                           case h :: Nil =>
                             Some(repo.db.open(h).openStream)
                           case _ =>
                             logger.error(s"There is more than one Technique with ID '${techniqueId.debugString}', what is forbidden. Please check if several categories have that Technique, and rename or delete the clones.")
                             Option.empty[ObjectStream]
                         }
                       } catch {
                         case ex:FileNotFoundException =>
                           logger.debug( () => "Template %s does not exist".format(path),ex)
                           Option.empty[ObjectStream]
                       }
                     }
         } yield optStream
    )(optStream => effectUioUnit(optStream.map(_.close())))

    managed.use(useIt)
  }

  override def getResourceContent[T](techniqueResourceId: TechniqueResourceId, postfixName: Option[String])(useIt : Option[InputStream] => IOResult[T]) : IOResult[T] = {
    //build a treewalk with the path, given by TechniqueTemplateId
    //here, we don't use rev in path, it will be used (if necessary) during walk
    val (rev, filenameFilter) = {
      val name = techniqueResourceId.name + postfixName.getOrElse("")
      techniqueResourceId match {
        case TechniqueResourceIdByName(tid, _) =>
          (tid.version.rev, new ExactFileTreeFilter(canonizedRelativePath, s"${tid.withDefaultRev.serialize}/${name}"))
        case TechniqueResourceIdByPath(Nil, rev, _) =>
          (rev, new ExactFileTreeFilter(None, name))
        case TechniqueResourceIdByPath(parents, rev, _) =>
          (rev, new ExactFileTreeFilter(Some(parents.mkString("/")), name))
      }
    }

    // since package id are unique among the whole tree, we are able to find a
    // template only base on the techniqueId + name.

    val managed = Managed.make(
      for {
        currentId <- GitFindUtils.findRevTreeFromRevision(repo.db, rev, revisionProvider.currentRevTreeId)
        optStream <- IOResult.effect {
                       try {
                         //now, the treeWalk
                         val tw = new TreeWalk(repo.db)
                         tw.setFilter(filenameFilter)
                         tw.setRecursive(true)
                         tw.reset(currentId)
                         var ids = List.empty[ObjectId]
                         while(tw.next) {
                           ids = tw.getObjectId(0) :: ids
                         }
                         ids match {
                           case Nil =>
                             logger.error(s"Template with id ${techniqueResourceId.displayPath} was not found")
                             Option.empty[ObjectStream]
                           case h :: Nil =>
                             Some(repo.db.open(h).openStream)
                           case _ =>
                             logger.error(s"There is more than one Technique with name '${techniqueResourceId.name}' which is forbidden. Please check if several categories have that Technique and rename or delete the clones")
                             Option.empty[ObjectStream]
                         }
                       } catch {
                         case ex:FileNotFoundException =>
                           logger.debug( () => s"Technique Template ${techniqueResourceId.displayPath} does not exist", ex)
                           Option.empty[ObjectStream]
                       }
                    }
      } yield optStream
    )(optStream => effectUioUnit(optStream.map(_.close())))

    managed.use(useIt)
  }

  /**
   * Read the policies from the last available tag.
   * The last available tag state is given by modifiedTechniques
   * and is ONLY updated by that method.
   * Two subsequent call to readTechniques without a call
   * to modifiedTechniques does nothing, even if some
   * commit were done in git repository.
   */
  override def readTechniques: TechniquesInfo = {
    semaphore.withPermit(
      for {
        needed <- needReloadPure
        info   <- if(needed) {
                    for {
                      next <- nextTechniquesInfoCache.get
                      _    <- currentTechniquesInfoCache.set(next._2)
                      _    <- revisionProvider.setCurrentRevTreeId(next._1)
                      _    <- modifiedTechniquesCache.set(Map())
                    } yield {
                      next._2
                    }
                  } else {
                    currentTechniquesInfoCache.get
                  }
      } yield {
        info
      }
    ).runNow
  }

  private[this] def needReloadPure = for {
    nextId  <- revisionProvider.currentRevTreeId
    cache   <- nextTechniquesInfoCache.get
  } yield nextId != cache._1

  override def needReload() = needReloadPure.runNow

  private[this] def processRevTreeId(db: Repository, id: ObjectId, parseDescriptor: Boolean = true) : IOResult[TechniquesInfo] = {
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

    for {
      techniqueInfosRef <- Ref.make(new InternalTechniquesInfo())
      //we only want path ending by a descriptor file

      //start to process all categories related information
      _                 <- processCategories(db, id, techniqueInfosRef, parseDescriptor)

      //now, build techniques
      _                 <- processTechniques(db, id, techniqueInfosRef, parseDescriptor)
      techniqueInfos    <- techniqueInfosRef.get
      defaulName        <- IOResult.effect( processDirectiveDefaultName(db, id))
    } yield {

      //ok, return the result in its immutable format
      TechniquesInfo(
          rootCategory       = techniqueInfos.rootCategory.get
        , gitRev             = id.name()
        , techniquesCategory = techniqueInfos.techniquesCategory.toMap
        , techniques         = techniqueInfos.techniques.map { case(k,v) => (k, SortedMap.empty[TechniqueVersion,Technique] ++ v)}.toMap
        , subCategories      = Map[SubTechniqueCategoryId, SubTechniqueCategory]() ++ techniqueInfos.subCategories
        , defaulName
      )
    }
  }

  private[this] def processDirectiveDefaultName(db: Repository, revTreeId: ObjectId) : Map[String, String] = {
      //a first walk to find categories
      val tw = new TreeWalk(db)
      tw.setFilter(new ExactFileTreeFilter(canonizedRelativePath, directiveDefaultName))
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
            is = db.open(tw.getObjectId(0)).openStream
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
      import scala.jdk.CollectionConverters._
      prop.asScala.toMap

  }

  private[this] def processTechniques(gitRepo: Repository, revTreeId: ObjectId, techniqueInfosRef: Ref[InternalTechniquesInfo], parseDescriptor: Boolean) : IOResult[Unit] = {
      //a first walk to find categories
    val managed = ZManaged.make(IOResult.effect {
      val tw = new TreeWalk(gitRepo)
      tw.setFilter(new ExactFileTreeFilter(canonizedRelativePath, techniqueDescriptorName))
      tw.setRecursive(true)
      tw.reset(revTreeId)
      tw
    })(tw => effectUioUnit(tw.close()))

    def rec(tw: TreeWalk): IOResult[Unit] = {
      IOResult.effect(tw.next()).flatMap { hasNext =>
        if(hasNext) {
          val path = toTechniquePath(tw.getPathString) //we will need it to build the category id
          val stream = ZManaged.make(IOResult.effect(gitRepo.open(tw.getObjectId(0)).openStream))(is => effectUioUnit(is.close()))
          for {
            _       <- processTechnique(stream, path.path, techniqueInfosRef, parseDescriptor, revTreeId).foldM(
                           err => TechniqueReaderLoggerPure.error(s"Error with technique at path: '${path.path}', it will be ignored. Error: ${err.fullMsg}")
                         , ok  => UIO.unit
                       )
            _       <- rec(tw)
          } yield ()
        } else UIO.unit
      }
    }

    managed.use(tw => rec(tw))
  }


  private[this] def processCategories(db: Repository, revTreeId: ObjectId, infosRef: Ref[InternalTechniquesInfo], parseDescriptor: Boolean): IOResult[Unit] = {
    //now, for each potential path, look if the cat or policy is valid
    def recBuildCat(db: Repository, cats: Map[TechniqueCategoryId, TechniqueCategory], tw: TreeWalk): IOResult[Map[TechniqueCategoryId, TechniqueCategory]] = {
      for {
        hasNext <- IOResult.effect(tw.next())
        res     <- if(hasNext) {
                     for {
                       id  <- IOResult.effect(tw.getObjectId(0))
                       path = toTechniquePath(tw.getPathString) //we will need it to build the category id
                       opt <- extractMaybeCategory(id, db, path.path, parseDescriptor).either
                       res <- opt match {
                                case Left(err)  =>
                                  TechniqueReaderLoggerPure.error(s"Error with category at path: '${path.path}', it will be ignored. Error: ${err.fullMsg}") *>
                                  recBuildCat(db, cats, tw)
                                case Right(cat) =>
                                  val updated = cats + (cat.id -> cat)
                                  recBuildCat(db, updated, tw)
                               }
                     } yield {
                       res
                     }
                   } else {
                     cats.succeed
                   }
      } yield res
    }

    @scala.annotation.tailrec
    def recBuildRoot(root: RootTechniqueCategory, techniqueInfos: InternalTechniquesInfo, subCategories: List[SubTechniqueCategoryId]): RootTechniqueCategory = {
      subCategories match {
        case Nil       => root
        case h :: tail => h match {
                            case sId @ SubTechniqueCategoryId(_, RootTechniqueCategoryId) => //update root
                              recBuildRoot(root.copy( subCategoryIds = root.subCategoryIds + sId ), techniqueInfos, tail)
                            case sId @ SubTechniqueCategoryId(_, pId: SubTechniqueCategoryId) =>
                              val cat = techniqueInfos.subCategories(pId)
                              techniqueInfos.subCategories(pId) = cat.copy( subCategoryIds = cat.subCategoryIds + sId )
                              recBuildRoot(root, techniqueInfos, tail)
                          }
      }
    }

    //a first walk to find categories
    val managed = ZManaged.make(IOResult.effect {
      val tw = new TreeWalk(db)
      tw.setFilter(new ExactFileTreeFilter(canonizedRelativePath, categoryDescriptorName))
      tw.setRecursive(true)
      tw.reset(revTreeId)
      tw
    })(tw => effectUioUnit(tw.close()))

    for {
      cats            <- managed.use(tw => recBuildCat(db, Map(), tw))
      toRemove        =  cats.flatMap {
                           case (sId:SubTechniqueCategoryId,cat:SubTechniqueCategory) =>
                             recToRemove(sId,Set[SubTechniqueCategoryId](), cats)
                           case _ => Set[SubTechniqueCategoryId]()
                         }
      //now, actually remove things
      maybeCategories =  cats -- toRemove
      //update techniqueInfos
      techniqueInfos  <- infosRef.get
      _               =  techniqueInfos.subCategories ++= maybeCategories.collect { case (sId: SubTechniqueCategoryId, cat: SubTechniqueCategory) => (sId -> cat) }
      root            <- maybeCategories.get(RootTechniqueCategoryId) match {
                          case None =>
                            val path = db.getWorkTree.getPath + canonizedRelativePath.map( "/" + _ + "/" + categoryDescriptorName).getOrElse("")
                            SystemError("Error when processing techniques in configuration repository",
                              NoRootCategory(s"Missing techniques root category in Git, expecting category descriptor for Git path: '${path}'")).fail
                          case Some(sub:SubTechniqueCategory) =>
                            TechniqueReaderLoggerPure.error("Bad type for root category in the Technique Library. Please check the hierarchy of categories") *>
                            SystemError("Error when processing techniques in configuration repository",
                              NoRootCategory(s"Bad type for root category in the Technique Library, found: '${sub.name}' [id:${sub.id.toString}]. Please check the hierarchy of categories")).fail
                          case Some(r: RootTechniqueCategory) => r.succeed
                        }
      updateRoot     =  recBuildRoot(root, techniqueInfos, techniqueInfos.subCategories.keySet.toList)
      //finally, update root !
      _              =  techniqueInfos.rootCategory = Some(updateRoot)
      _              <- infosRef.set(techniqueInfos)
    } yield ()
  }


  /**
   * We remove each category for which parent category is not defined.
   */
  @scala.annotation.tailrec
  private[this] def recToRemove(
      catId          : SubTechniqueCategoryId
    , toRemove       : Set[SubTechniqueCategoryId]
    , maybeCategories: Map[TechniqueCategoryId, TechniqueCategory]
  ) : Set[SubTechniqueCategoryId] = {
      catId.parentId match {
        case RootTechniqueCategoryId => toRemove
        case sId:SubTechniqueCategoryId =>
          if(toRemove.contains(sId)) {
            toRemove + catId
          } else if(maybeCategories.isDefinedAt(sId)) {
            recToRemove(sId, toRemove, maybeCategories )
          } else {
            toRemove + catId
          }
      }
  }

  private[this] val dummyTechnique = Technique(
      TechniqueId(TechniqueName("dummy"), TechniqueVersion.parse("1.0").getOrElse(throw new RuntimeException("Version of dummy technique is not parsable")))
    , "dummy", "dummy", Nil, TrackerVariableSpec(id = None)
    , SectionSpec("ROOT"), None
 )

  private[this] def processTechnique(
      is             : ZManaged[Any, RudderError, InputStream]
    , filePath       : String
    , techniquesInfo : Ref[InternalTechniquesInfo]
    , parseDescriptor: Boolean // that option is a success optimization for the case diff between old/new commit
    , revTreeId      : ObjectId
  ): IOResult[Unit] = {
    def updateParentCat(info: InternalTechniquesInfo, catId: TechniqueCategoryId, techniqueId: TechniqueId, descriptorFile: File) : Boolean = {
      catId match {
        case RootTechniqueCategoryId =>
          val cat = info.rootCategory.getOrElse(
              throw new RuntimeException(s"Can not find the parent (root) category '${descriptorFile.getParent}' for technique '${techniqueId.debugString}'")
          )
          info.rootCategory = Some(cat.copy(techniqueIds = cat.techniqueIds.union(Set(techniqueId) )))
          true

        case sid:SubTechniqueCategoryId =>
          info.subCategories.get(sid) match {
            case Some(cat) =>
              info.subCategories(sid) = cat.copy(techniqueIds = cat.techniqueIds.union(Set(techniqueId) ))
              true
            case None =>
              logger.error(s"Can not find the parent (root) category '${descriptorFile.getParent}' for technique '${techniqueId.debugString}'")
              false
          }
      }
    }

    val descriptorFile = new File(filePath)
    val policyName = TechniqueName(descriptorFile.getParentFile.getParentFile.getName)
    val parentCategoryId = TechniqueCategoryId.buildId(descriptorFile.getParentFile.getParentFile.getParent )

    for {
      policyVersion <- ZIO.fromEither(TechniqueVersion.parse(descriptorFile.getParentFile.getName)).mapError(s => Unexpected(s))
      techniqueId   = TechniqueId(policyName,policyVersion)
      pack <- if(parseDescriptor) loadDescriptorFile(is, filePath).flatMap(d => ZIO.fromEither(techniqueParser.parseXml(d, techniqueId)))
              else dummyTechnique.succeed
      info <- techniquesInfo.get
      res  <- Task.effect {
                //check that that package is not already know, else its an error (by id ?)
                info.techniques.get(techniqueId.name) match {
                  case None => //so we don't have any version yet, and so no id
                    if(updateParentCat(info,parentCategoryId, techniqueId, descriptorFile)) {
                      info.techniques(techniqueId.name) = MutMap(techniqueId.version -> pack)
                      info.techniquesCategory(techniqueId) = parentCategoryId
                    }
                  case Some(versionMap) => //check for the version
                    versionMap.get(techniqueId.version) match {
                      case None => //add that version
                        if(updateParentCat(info, parentCategoryId, techniqueId, descriptorFile)) {
                          info.techniques(techniqueId.name)(techniqueId.version) = pack
                          info.techniquesCategory(techniqueId) = parentCategoryId
                        }
                      case Some(v) => //error, policy package version already exsits
                        logger.error("Ignoring package for policy with ID %s and root directory %s because an other policy is already defined with that id and root path %s".format(
                            TechniqueId, descriptorFile.getParent, info.techniquesCategory(techniqueId).toString)
                        )
                    }
                }
              }.fold ( err => err match {
                case e : ConstraintException => s"Ignoring technique '${filePath}}' because the descriptor file is malformed. Error message was: ${e.getMessage}}".fail
                case e : Throwable => s"Error when processing technique '${filePath}}': ${e.getMessage}}".fail
              }
              , ok => techniquesInfo.set(info)
              )
    }yield {
      ()
    }
  }

  /**
   * Register a category, but without checking that its parent
   * is legal.
   * So that will lead to an inconsistant Map of categories
   * which must be normalized before use !
   *
   * If the category descriptor is here, but incorrect,
   * we assume that the directory should be considered
   * as a category, but the user did a mistake: signal it,
   * but DO use the folder as a category.
   */
  private[this] def extractMaybeCategory(
      descriptorObjectId: ObjectId
    , db                : Repository
    , filePath          : String
    , parseDescriptor   : Boolean // that option is a success optimization for the case diff between old/new commit
  ) : IOResult[TechniqueCategory] = {

    def nonEmpty(s: String): Option[String] = {
      s match {
        case null | "" => None
        case _ => Some(s)
      }
    }
    def parse(db: Repository, parseDesc:Boolean, catId: TechniqueCategoryId): IOResult[(String, String, Boolean)] = {
      if(parseDesc) {
        val managedStream = ZManaged.make(IOResult.effect(db.open(descriptorObjectId).openStream))(is => effectUioUnit(is.close()))
        for {
          xml <- loadDescriptorFile(managedStream, filePath)
        } yield {
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
      triple <- parse(db, parseDescriptor, catId)
    } yield {
      val (name, desc, system ) = triple
      catId match {
        case RootTechniqueCategoryId    => RootTechniqueCategory(name, desc, isSystem = system)
        case sId:SubTechniqueCategoryId => SubTechniqueCategory(sId, name, desc, isSystem = system)
      }
    }
  }

  /**
   * Load a descriptor document.
   */
  private[this] def loadDescriptorFile(managedStream: ZManaged[Any, RudderError, InputStream], filePath : String ) : IOResult[Elem] = {
    managedStream.use(is =>
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
  private[this] def getTechniquePath(techniqueInfos: TechniquesInfo) : Set[(TechniquePath, TechniqueId)] = {
    val set = scala.collection.mutable.Set[(TechniquePath, TechniqueId)]()
    techniqueInfos.rootCategory.techniqueIds.foreach { id =>
      set += ((TechniquePath( "/" + id.serialize), id))
    }
    techniqueInfos.subCategories.foreach { case (id,cat) =>
      val path = id.toString
      cat.techniqueIds.foreach { t => set += ((TechniquePath(path + "/" + t.serialize), t)) }
    }
    //also add template "by path"
    val techniques = techniqueInfos.techniques.flatMap { case(_, set) => set.map { case(_, t) => t } }
    techniques.foreach { t =>
      val byPath = t.agentConfigs.flatMap(cfg => cfg.templates.collect { case TechniqueTemplate(id@TechniqueResourceIdByPath(_,_,_),_,_) => id }) ++
                   t.agentConfigs.flatMap(cfg => cfg.files.collect { case TechniqueFile(id@TechniqueResourceIdByPath(_,_,_),_,_) => id })
      byPath.foreach { resource =>
        //here, "/" is needed at the begining because diffEntry have one, so if we don't
        //add it, we won't find is back in modifiedTechnique and diffPathEntries
        set += ((TechniquePath(resource.parentDirectories.mkString("/", "/", "/") + resource.name), t.id))
      }
    }
    set.toSet
   }
}
