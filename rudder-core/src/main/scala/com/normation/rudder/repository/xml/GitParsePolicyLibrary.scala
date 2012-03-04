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

import scala.Option.option2Iterable

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevTag

import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.cfclerk.services.GitRevisionProvider
import com.normation.rudder.repository._
import com.normation.rudder.services.marshalling.DirectiveUnserialisation
import com.normation.rudder.services.marshalling.ActiveTechniqueCategoryUnserialisation
import com.normation.rudder.services.marshalling.ActiveTechniqueUnserialisation
import com.normation.utils.Control._
import com.normation.utils.UuidRegex
import com.normation.utils.XmlUtils

import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full


class GitParseActiveTechniqueLibrary(
    categoryUnserialiser: ActiveTechniqueCategoryUnserialisation
  , uptUnserialiser     : ActiveTechniqueUnserialisation
  , piUnserialiser      : DirectiveUnserialisation
  , repo                : GitRepositoryProvider
  , libRootDirectory    : String //relative name to git root file
  , uptcFileName        : String = "category.xml"
  , uptFileName         : String = "activeTechniqueSettings.xml"    
) extends ParseActiveTechniqueLibrary {
  
  def getArchive(archiveId:GitCommitId) = {
    for {
      treeId  <- GitFindUtils.findRevTreeFromRevString(repo.db, archiveId.value)
      archive <- getArchiveForRevTreeId(treeId)
    } yield {
      archive
    }
  }

  private[this] def getArchiveForRevTreeId(revTreeId:ObjectId) = {

    val root = {
      val p = libRootDirectory.trim
      if(p.size == 0) ""
      else if(p.endsWith("/")) p 
      else p + "/" 
    }

    //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
    val paths = GitFindUtils.listFiles(repo.db, revTreeId, List(root.substring(0, root.size-1)), Nil)
        
    //directoryPath must end with "/"
    def recParseDirectory(directoryPath:String) : Box[Either[ActiveTechniqueCategoryContent, ActiveTechniqueContent]] = {

      val category = directoryPath + uptcFileName
      val template = directoryPath + uptFileName

      (paths.contains(category), paths.contains(template)) match {
        //herrr... skip that one
        case (false, false) => Failure("The directory '%s' does not contain '%s' or '%s' and should not have been considered".format(directoryPath,category,template))
        case (true, true) => Failure("The directory '%s' contains both '%s' and '%s' descriptor file. Only one of them is authorized".format(directoryPath, uptcFileName, uptFileName))
        case (true, false) =>
          // that's the directory of an ActiveTechniqueCategory. 
          // ignore files other than uptcFileName (parsed as an ActiveTechniqueCategory), recurse on sub-directories
          // don't forget to sub-categories and UPT and UPTC
          for {
            uptcXml  <- GitFindUtils.getFileContent(repo.db, revTreeId, category){ inputStream =>
                          XmlUtils.parseXml(inputStream, Some(category)) ?~! "Error when parsing file '%s' as a category".format(category)
                        }
            uptc     <- categoryUnserialiser.unserialise(uptcXml) ?~! "Error when unserializing category for file '%s'".format(category)
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
            subItems <- sequence(subDirs.toSeq) { dir =>
                          recParseDirectory(dir)
                        }
          } yield {
            val subCats = subItems.collect { case Left(x) => x }.toSet
            val upts = subItems.collect { case Right(x) => x }.toSet
            
            val category = uptc.copy(
                children = subCats.map { case ActiveTechniqueCategoryContent(cat, _, _) => cat.id }.toList
              , items = upts.map { case ActiveTechniqueContent(activeTechnique, _) => activeTechnique.id}.toList
            )
            
            Left(ActiveTechniqueCategoryContent(category, subCats, upts))
          }
          
        case (false, true) => 
          // that's the directory of an ActiveTechnique
          // ignore sub-directories, parse uptFileName as an ActiveTechnique, parse UUID.xml as PI
          // don't forget to add PI ids to UPT
          for {
            uptXml  <- GitFindUtils.getFileContent(repo.db, revTreeId, template){ inputStream =>
                         XmlUtils.parseXml(inputStream, Some(template)) ?~! "Error when parsing file '%s' as a category".format(template)
                       }
            activeTechnique     <- uptUnserialiser.unserialise(uptXml) ?~! "Error when unserializing template for file '%s'".format(template)
            piFiles =  {
                         paths.filter { p =>
                           p.size > directoryPath.size && 
                           p.startsWith(directoryPath) &&
                           p.endsWith(".xml") &&
                           UuidRegex.isValid(p.substring(directoryPath.size, p.size - 4))
                         }
                       }
            directives     <- sequence(piFiles.toSeq) { piFile =>
                         for {
                           piXml      <-  GitFindUtils.getFileContent(repo.db, revTreeId, piFile){ inputStream =>
                                            XmlUtils.parseXml(inputStream, Some(piFile)) ?~! "Error when parsing file '%s' as a policy instance".format(piFile)
                                          }
                           (_, directive, _) <-  piUnserialiser.unserialise(piXml) ?~! "Error when unserializing ppolicy instance for file '%s'".format(piFile)
                         } yield {
                           directive
                         }
                       }
          } yield {
            val pisSet = directives.toSet
            Right(ActiveTechniqueContent(
                activeTechnique.copy(directives = pisSet.map(_.id).toList)
              , pisSet
            ))
          }
      }
    }
    
    recParseDirectory(root) match {
      case Full(Left(x)) => Full(x)
      
      case Full(Right(x)) => 
        Failure("We found an User Policy Template where we were expected the root of user policy library, and so a category. Path: '%s'; found: '%s'".format(
            root, x.activeTechnique))
      
      case Empty => Failure("Error when parsing the root directory for policy library '%s'. Perhaps the '%s' file is missing in that directory, or the saved policy library was not correctly exported".format(
                      root, uptcFileName
                    ) )
                    
      case f:Failure => f
    }
  }
}
