/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.ncf

import better.files.File
import com.normation.errors.IOResult
import com.normation.rudder.git.GitFindUtils
import com.normation.rudder.git.GitRepositoryProvider

/* Provide a Service to handle resource files of a Technique
 * And implementation based on file system and git is provided here
 */

trait ResourceFileService {
  def getResources(technique:            EditorTechnique): IOResult[List[ResourceFile]]
  def getResourcesFromDir(resourcesPath: String, techniqueName: String, techniqueVersion: String): IOResult[List[ResourceFile]]
}
class GitResourceFileService(gitReposProvider: GitRepositoryProvider) extends ResourceFileService {
  def getResources(technique: EditorTechnique) = {
    getResourcesFromDir(
      s"techniques/${technique.category}/${technique.id.value}/${technique.version.value}/resources",
      technique.id.value,
      technique.version.value
    )

  }

  def getResourcesFromDir(resourcesPath: String, techniqueName: String, techniqueVersion: String) = {

    def getAllFiles(file: File): List[String] = {
      if (file.exists) {
        if (file.isRegularFile) {
          (gitReposProvider.rootDirectory / resourcesPath).relativize(file).toString :: Nil
        } else {
          file.children.toList.flatMap(getAllFiles)
        }
      } else {
        Nil
      }
    }

    import scala.jdk.CollectionConverters._
    import ResourceFileState._

    def toResource(resourcesPath: String)(fullPath: String, state: ResourceFileState): Option[ResourceFile] = {
      // workaround https://issues.rudder.io/issues/17977 - if the fullPath does not start by resourcePath,
      // it's a bug from jgit filtering: ignore that file
      val relativePath = fullPath.stripPrefix(s"${resourcesPath}/")
      if (relativePath == fullPath) None
      else Some(ResourceFile(relativePath, state))
    }
    for {

      status     <- GitFindUtils
                      .getStatus(gitReposProvider.git, List(resourcesPath))
                      .chainError(
                        s"Error when getting status of resource files of technique ${techniqueName}/${techniqueVersion}"
                      )
      resourceDir = File(gitReposProvider.db.getDirectory.getParent, resourcesPath)
      allFiles   <- IOResult.attempt(s"Error when getting all resource files of technique ${techniqueName}/${techniqueVersion} ") {
                      getAllFiles(resourceDir)
                    }
    } yield {

      val toResourceFixed = toResource(resourcesPath) _

      // New files not added
      val added    = status.getUntracked.asScala.toList.flatMap(toResourceFixed(_, New))
      // Files modified and not added
      val modified = status.getModified.asScala.toList.flatMap(toResourceFixed(_, Modified))
      // Files deleted but not removed from git
      val removed  = status.getMissing.asScala.toList.flatMap(toResourceFixed(_, Deleted))

      val filesNotCommitted = modified ::: added ::: removed

      // We want to get all files from the resource directory and remove all added/modified/deleted files so we can have the list of all files not modified
      val untouched = allFiles.filterNot(f => filesNotCommitted.exists(_.path == f)).map(ResourceFile(_, Untouched))

      // Create a new list with all a
      filesNotCommitted ::: untouched
    }
  }
}
