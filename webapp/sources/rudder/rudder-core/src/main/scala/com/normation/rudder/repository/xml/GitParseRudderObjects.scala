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

package com.normation.rudder.repository.xml

import com.normation.NamedZioLogger
import org.eclipse.jgit.lib.ObjectId
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.errors.IOResult
import com.normation.rudder.repository._
import com.normation.rudder.services.marshalling.RuleUnserialisation
import com.normation.utils.Control._
import com.normation.utils.UuidRegex
import net.liftweb.common.Loggable
import com.normation.rudder.migration.XmlEntityMigration
import com.normation.errors._
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleTargetInfo
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.services.marshalling.ActiveTechniqueCategoryUnserialisation
import com.normation.rudder.services.marshalling.ActiveTechniqueUnserialisation
import com.normation.rudder.services.marshalling.DirectiveUnserialisation
import com.normation.rudder.services.marshalling.GlobalParameterUnserialisation
import com.normation.rudder.services.marshalling.NodeGroupCategoryUnserialisation
import com.normation.rudder.services.marshalling.NodeGroupUnserialisation
import com.normation.rudder.services.marshalling.RuleCategoryUnserialisation
import scalaz.zio._
import scalaz.zio.syntax._


final case class GitRootCategory(
    root: String
) {
  def directoryPath = root + "/"
}
// utilities
trait GitParseCommon[T] {

  def repo: GitRepositoryProvider

  def getArchive(archiveId: GitCommitId): IOResult[T] = {
    for {
      db      <- repo.db
      treeId  <- GitFindUtils.findRevTreeFromRevString(db, archiveId.value)
      archive <- getArchiveForRevTreeId(treeId)
    } yield {
      archive
    }
  }

  def getArchiveForRevTreeId(revTreeId:ObjectId): IOResult[T]

  def getGitDirectoryPath(rootDirectory: String): GitRootCategory = {
    val root = {
      val p = rootDirectory.trim
      if(p.size == 0) ""
      else if(p.endsWith("/")) p.substring(0, p.size-1)
      else p
    }

    GitRootCategory(root)
  }
}

class GitParseRules(
    ruleUnserialisation: RuleUnserialisation
  , val repo           : GitRepositoryProvider
  , xmlMigration       : XmlEntityMigration
  , rulesRootDirectory : String //relative name to git root file
) extends ParseRules with GitParseCommon[List[Rule]] {

  def getArchiveForRevTreeId(revTreeId:ObjectId): IOResult[List[Rule]] = {

    val root = getGitDirectoryPath(rulesRootDirectory)

    for {
      db    <- repo.db
      //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
      files <- GitFindUtils.listFiles(db, revTreeId, List(root.root), List(".xml"))
      paths =  files.filter { p =>
                   p.size > root.directoryPath.size &&
                   p.startsWith(root.directoryPath) &&
                   p.endsWith(".xml") &&
                   UuidRegex.isValid(p.substring(root.directoryPath.size,p.size - 4))
               }
      xmls  <- ZIO.foreach(paths) { crPath =>
                 GitFindUtils.getFileContent(db, revTreeId, crPath){ inputStream =>
                   ParseXml(inputStream, Some(crPath)).toIO
                 }
               }
      rules <- ZIO.foreach(xmls) { xml =>
                 for {
                   ruleXml <- xmlMigration.getUpToDateXml(xml).toIO
                   rule    <- ruleUnserialisation.unserialise(ruleXml).toIO
                 } yield {
                   rule
                 }
               }
    } yield {
      rules
    }
  }
}


class GitParseGlobalParameters(
    paramUnserialisation    : GlobalParameterUnserialisation
  , val repo                : GitRepositoryProvider
  , xmlMigration            : XmlEntityMigration
  , parametersRootDirectory : String //relative name to git root file
) extends ParseGlobalParameters with GitParseCommon[List[GlobalParameter]] {

  def getArchiveForRevTreeId(revTreeId:ObjectId): IOResult[List[GlobalParameter]] = {

    val root = getGitDirectoryPath(parametersRootDirectory)

    //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
    for {
      db     <- repo.db
      files  <- GitFindUtils.listFiles(db, revTreeId, List(root.root), List(".xml"))
      paths  =  files.filter { p =>
                   p.size > root.directoryPath.size &&
                   p.startsWith(root.directoryPath) &&
                   p.endsWith(".xml")
                 }
      xmls   <- ZIO.foreach(paths.toSeq) { paramPath =>
                  GitFindUtils.getFileContent(db, revTreeId, paramPath){ inputStream =>
                    ParseXml(inputStream, Some(paramPath)).toIO
                  }
                }
      params <- ZIO.foreach(xmls) { xml =>
                    for {
                      paramXml <- xmlMigration.getUpToDateXml(xml).toIO
                      param    <- paramUnserialisation.unserialise(paramXml).toIO
                    } yield {
                      param
                    }
                }
    } yield {
      params
    }
  }
}

class GitParseRuleCategories(
    unserialiser       : RuleCategoryUnserialisation
  , val repo           : GitRepositoryProvider
  , xmlMigration       : XmlEntityMigration
  , rulesRootDirectory : String //relative name to git root file
  , categoryFileName   : String = "category.xml"
) extends ParseRuleCategories with GitParseCommon[RuleCategory] {

  def getArchiveForRevTreeId(revTreeId:ObjectId) = {

    //directoryPath must end with "/"
    def recParseDirectory(paths: Set[String], directoryPath: String) : IOResult[RuleCategory] = {

      val categoryPath = directoryPath + categoryFileName
      // that's the directory of a RuleCategory.
      // don't forget to recurse sub-categories
      for {
        db           <- repo.db
        xml          <- GitFindUtils.getFileContent(db, revTreeId, categoryPath){ inputStream =>
                          ParseXml(inputStream, Some(categoryPath)).toIO.chainError(s"Error when parsing file '${categoryPath}' as a category")
                        }
        categoryXml  <- xmlMigration.getUpToDateXml(xml).toIO
        category     <- unserialiser.unserialise(categoryXml).toIO.chainError(s"Error when unserializing category for file '${categoryPath}'")
        subDirs      =  {
                          //we only wants to keep paths that are non-empty directories with a rulecategory filename (category.xml)
                          paths.flatMap { p =>
                            if(p.size > directoryPath.size && p.startsWith(directoryPath)) {
                              val split = p.substring(directoryPath.size).split("/")
                              if(split.size == 2 && (split(1) == categoryFileName) ) {
                                Some(directoryPath + split(0) + "/")
                              } else None
                            } else None
                          }
                        }
        subCats      <- ZIO.foreach(subDirs.toSeq) { dir =>
                          recParseDirectory(paths, dir)
                        }
      } yield {
        category.copy(childs = subCats.toList)
      }
    }

    val root = getGitDirectoryPath(rulesRootDirectory)

    for {
      db    <- repo.db
      //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
      paths <- GitFindUtils.listFiles(db, revTreeId, List(root.root), List(".xml"))
      res   <- recParseDirectory(paths, root.directoryPath)
    } yield {
      res
    }
  }
}

class GitParseGroupLibrary(
    categoryUnserialiser: NodeGroupCategoryUnserialisation
  , groupUnserialiser   : NodeGroupUnserialisation
  , val repo            : GitRepositoryProvider
  , xmlMigration        : XmlEntityMigration
  , libRootDirectory    : String //relative name to git root file
  , categoryFileName    : String = "category.xml"
) extends ParseGroupLibrary with GitParseCommon[NodeGroupCategoryContent] {

  def getArchiveForRevTreeId(revTreeId:ObjectId): IOResult[NodeGroupCategoryContent] = {

    //directoryPath must end with "/"
    def recParseDirectory(paths: Set[String], directoryPath:String) : IOResult[NodeGroupCategoryContent] = {

      val categoryPath = directoryPath + categoryFileName

      // that's the directory of an NodeGroupCategory.
      // ignore files other than NodeGroup (UUID.xml) and directories
      // don't forget to recurse sub-categories
      for {
        db           <- repo.db
        xml          <- GitFindUtils.getFileContent(db, revTreeId, categoryPath){ inputStream =>
                          ParseXml(inputStream, Some(categoryPath)).toIO.chainError(s"Error when parsing file '${categoryPath}' as a category")
                        }
        categoryXml  <- xmlMigration.getUpToDateXml(xml).toIO
        category     <- categoryUnserialiser.unserialise(categoryXml).toIO.chainError(s"Error when unserializing category for file '${categoryPath}'")
        groupFiles   =  {
                          paths.filter { p =>
                            p.size > directoryPath.size &&
                            p.startsWith(directoryPath) &&
                            p.endsWith(".xml") &&
                            UuidRegex.isValid(p.substring(directoryPath.size,p.size - 4))
                          }
                        }
        groups       <- ZIO.foreach(groupFiles.toSeq) { groupPath =>
                          for {
                            xml2     <- GitFindUtils.getFileContent(db, revTreeId, groupPath){ inputStream =>
                              ParseXml(inputStream, Some(groupPath)).toIO.chainError(s"Error when parsing file '${groupPath}' as a directive")
                            }
                            groupXml <- xmlMigration.getUpToDateXml(xml2).toIO
                            group    <- groupUnserialiser.unserialise(groupXml).toIO.chainError(s"Error when unserializing group for file '${groupPath}'")
                          } yield {
                            group
                          }
                        }
        subDirs      =  {
                          //we only wants to keep paths that are non-empty directories with a uptcFileName/uptFileName in them
                          paths.flatMap { p =>
                            if(p.size > directoryPath.size && p.startsWith(directoryPath)) {
                              val split = p.substring(directoryPath.size).split("/")
                              if(split.size == 2 && (split(1) == categoryFileName) ) {
                                Some(directoryPath + split(0) + "/")
                              } else None
                            } else None
                          }
                        }
        subCats      <- ZIO.foreach(subDirs.toSeq) { dir =>
                          recParseDirectory(paths, dir)
                        }
      } yield {
        val s = subCats.toSet
        val g = groups.toSet

        val cat = category.copy(
            children = s.map { _.category.id }.toList
          , items = g.map { x =>
                      RuleTargetInfo(
                          target      = GroupTarget(x.id)
                        , name        = x.name
                        , description = x.description
                        , isEnabled = x.isEnabled
                        , isSystem    = x.isSystem
                      )
                    }.toList
        )

        NodeGroupCategoryContent(cat, s, g)
      }
    }

    val root = getGitDirectoryPath(libRootDirectory)

    for {
      db    <- repo.db
      //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
      paths <- GitFindUtils.listFiles(db, revTreeId, List(root.root.substring(0, root.root.size-1)), Nil)
      res   <- recParseDirectory(paths, root.directoryPath)
    } yield {
      res
    }
  }
}

class GitParseActiveTechniqueLibrary(
    categoryUnserialiser: ActiveTechniqueCategoryUnserialisation
  , uptUnserialiser     : ActiveTechniqueUnserialisation
  , piUnserialiser      : DirectiveUnserialisation
  , val repo            : GitRepositoryProvider
  , xmlMigration        : XmlEntityMigration
  , libRootDirectory    : String //relative name to git root file
  , uptcFileName        : String = "category.xml"
  , uptFileName         : String = "activeTechniqueSettings.xml"
) extends ParseActiveTechniqueLibrary with GitParseCommon[ActiveTechniqueCategoryContent] {

  def getArchiveForRevTreeId(revTreeId:ObjectId): IOResult[ActiveTechniqueCategoryContent] = {

    //directoryPath must end with "/"
    def recParseDirectory(paths: Set[String], directoryPath: String) : IOResult[Either[ActiveTechniqueContent, ActiveTechniqueCategoryContent]] = {

      val category = directoryPath + uptcFileName
      val template = directoryPath + uptFileName

      (paths.contains(category), paths.contains(template)) match {
        //herrr... skip that one
        case (false, false) => Unconsistancy(s"The directory '${directoryPath}' does not contain '${category}' or '${template}' and should not have been considered").fail
        case (true, true) => Unconsistancy(s"The directory '${directoryPath}' contains both '${uptcFileName}' and '${uptFileName}' descriptor file. Only one of them is authorized").fail
        case (true, false) =>
          // that's the directory of an ActiveTechniqueCategory.
          // ignore files other than uptcFileName (parsed as an ActiveTechniqueCategory), recurse on sub-directories
          // don't forget to sub-categories and UPT and UPTC
          for {
            db       <- repo.db
            xml      <- GitFindUtils.getFileContent(db, revTreeId, category){ inputStream =>
                          ParseXml(inputStream, Some(category)).toIO.chainError(s"Error when parsing file '${category}' as a category")
                        }
            //here, we have to migrate XML fileformat, if not up to date
            uptcXml  <- xmlMigration.getUpToDateXml(xml).toIO
            uptc     <- categoryUnserialiser.unserialise(uptcXml).toIO.chainError(s"Error when unserializing category for file '${category}'")
            subDirs  =  {
                          //we only wants to keep paths that are non-empty directories with a uptcFileName/uptFileName in them
                          paths.flatMap { p =>
                            if(p.size > directoryPath.size && p.startsWith(directoryPath)) {
                              val split = p.substring(directoryPath.size).split("/")
                              if(split.size == 2 && (split(1) == uptcFileName || split(1) == uptFileName) ) {
                                Some(directoryPath + split(0) + "/")
                              } else None
                            } else None
                          }
                        }
            subItems <- ZIO.foreach(subDirs.toSeq) { dir =>
                          recParseDirectory(paths, dir)
                        }
          } yield {
            val subCats = subItems.collect { case Right(x) => x }.toSet
            val upts    = subItems.collect { case Left(x)  => x }.toSet

            val category = uptc.copy(
                children = subCats.map { case ActiveTechniqueCategoryContent(cat, _, _)  => cat.id }.toList
              , items    = upts.map    { case ActiveTechniqueContent(activeTechnique, _) => activeTechnique.id}.toList
            )

            Right(ActiveTechniqueCategoryContent(category, subCats, upts))
          }

        case (false, true) =>
          // that's the directory of an ActiveTechnique
          // ignore sub-directories, parse uptFileName as an ActiveTechnique, parse UUID.xml as PI
          // don't forget to add PI ids to UPT
          for {
            db     <- repo.db
            xml    <- GitFindUtils.getFileContent(db, revTreeId, template){ inputStream =>
                         ParseXml(inputStream, Some(template)).toIO.chainError(s"Error when parsing file '${template}' as a category")
                       }
            uptXml  <- xmlMigration.getUpToDateXml(xml).toIO
            activeTechnique <- uptUnserialiser.unserialise(uptXml).toIO.chainError(s"Error when unserializing template for file '${template}'")
            piFiles =  {
                         paths.filter { p =>
                           p.size > directoryPath.size &&
                           p.startsWith(directoryPath) &&
                           p.endsWith(".xml") &&
                           UuidRegex.isValid(p.substring(directoryPath.size, p.size - 4))
                         }
                       }
            directives <- ZIO.foreach(piFiles.toSeq) { piFile =>
                         for {
                           xml2  <- GitFindUtils.getFileContent(db, revTreeId, piFile){ inputStream =>
                                      ParseXml(inputStream, Some(piFile)).toIO.chainError(s"Error when parsing file '${piFile}' as a directive")
                                    }
                           piXml <- xmlMigration.getUpToDateXml(xml2).toIO
                           res   <- piUnserialiser.unserialise(piXml).toIO.chainError(s"Error when unserializing pdirective for file '${piFile}'")
                         } yield {
                           val (_, directive, _) = res
                           directive
                         }
                       }
          } yield {
            val pisSet = directives.toSet
            Left(ActiveTechniqueContent(
                activeTechnique.copy(directives = pisSet.map(_.id).toList)
              , pisSet
            ))
          }
      }
    }

    val root = getGitDirectoryPath(libRootDirectory)

    (for {
      db    <- repo.db
      paths <- GitFindUtils.listFiles(db, revTreeId, List(root.root.substring(0, root.root.size-1)), Nil)
      //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
      res   <- recParseDirectory(paths, root.root)
    } yield {
      res
    }).flatMap {
      case Left(x)  => Unconsistancy(s"We found an Active Technique where we were expected the root of active techniques library, and so a category. Path: '${root.root}'; found: '${x.activeTechnique}'").fail
      case Right(x) => x.succeed
    }
  }
}
