package com.normation.rudder.ncf

import java.time.Instant

import better.files._
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.errors._
import com.normation.eventlog.ModificationId
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.hooks.RunNuCommand
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.GitArchiverUtils
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.StringUuidGenerator
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.parse
import zio.ZIO
import zio.ZIO._
import zio.syntax._
import com.normation.rudder.domain.eventlog.RudderEventActor

class TechniqueReader(
    restExtractor                 : RestExtractorService
  , uuidGen                       : StringUuidGenerator
  , personIdentService            : PersonIdentService
  , override val gitRepo          : GitRepositoryProvider
  , override val gitRootDirectory : java.io.File
  , override val xmlPrettyPrinter : RudderPrettyPrinter
  , override val gitModificationRepository : GitModificationRepository
  , override val encoding         : String = "UTF-8"
) extends GitArchiverUtils {
  import better.files.File.root
  override val relativePath     : String = "ncf"
  val configuration_repository = gitRootDirectory.toScala
  val ncfRootDir = configuration_repository / relativePath
  val methodsFile = ncfRootDir / "generic_methods.json"



  def getAllTechniqueFiles(currentPath : File): IOResult[List[File]]= {
    import com.normation.errors._
    for {
      subdirs       <- IOResult.effect (s"error when getting subdirectories of ${currentPath.pathAsString}") (currentPath.children.partition(_.isDirectory)._1.toList)
      checkSubdirs  <- foreach(subdirs)(getAllTechniqueFiles).map(_.flatten)
      techniqueFilePath : File = currentPath / "technique.json"
      result        <- IOResult.effect(
                         if (techniqueFilePath.exists) {
                           techniqueFilePath :: checkSubdirs
                         } else {
                           checkSubdirs
                         }
                       )
    } yield {
      result
    }
  }
  def readTechniquesMetadataFile: IOResult[List[Technique]] = {
    for {
      methods        <- readMethodsMetadataFile
      techniqueFiles <- getAllTechniqueFiles(configuration_repository / "techniques")
      techniques     <- foreach(techniqueFiles)( file =>
                          restExtractor.extractNcfTechnique(parse(file.contentAsString), methods, false).toIO
                            .chainError("An Error occured while extracting data from techniques ncf API")
                        )
    } yield {
      techniques
    }
  }

  private[this] def checkNeedsUpdate(methodsFileModifiedTime: Instant, dirs: List[File]) : Boolean = {
    def isAMethodNewerThanCache(file : File) : Boolean = {
      file.isRegularFile && file.extension(false).map(_ == "cf").getOrElse(false)  && file.lastModifiedTime.isAfter(methodsFileModifiedTime)
    }
    dirs.exists(_.collectChildren(isAMethodNewerThanCache).isEmpty)
  }

  private[this] def doesMethodsMetadataFileNeedsUpdate: IOResult[Boolean]  = {
    val baseDir = root / "usr" / "share" / "ncf" / "tree" / "30_generic_methods"
    val userDir = ncfRootDir / "30_generic_methods"
    for {
      metadataFileExists <- IOResult.effect(methodsFile.exists)
      needsUpdate <- if (metadataFileExists) {
                       IOResult.effect("An error occurs while checking if generic methods metadata needs to be updated") {
                         val methodsFileModifiedTime = methodsFile.lastModifiedTime()
                         val dirsToCheck = (baseDir :: userDir :: Nil).filter(_.exists)
                         // Not sure what to do if empty; here it will be false, and we don't update, but updating would surely be an error
                         checkNeedsUpdate(methodsFileModifiedTime, dirsToCheck)
                       }
                     } else true.succeed
    } yield {
      // File does not exists or a cf file is newer than the metadata file
      needsUpdate
    }
  }

  def readMethodsMetadataFile : IOResult[Map[BundleName, GenericMethod]] = {
    for {
      _                    <- ZIO.whenM(doesMethodsMetadataFileNeedsUpdate) {
                                updateMethodsMetadataFile.map(_.code).unit
                              }
      genericMethodContent <- IOResult.effect(s"error while reading ${methodsFile.pathAsString}")(methodsFile.contentAsString)
      methods              <- parse(genericMethodContent) match {
                                case JObject(fields) =>
                                  restExtractor.extractGenericMethod(JArray(fields.map(_.value))).map(_.map(m => (m.id, m)).toMap).toIO
                                  .chainError(s"An Error occured while extracting data from generic methods ncf API")

                                case a => Inconsistency(s"Could not extract methods from ncf api, expecting an object got: ${a}").fail
                              }
    } yield {
      methods
    }
  }

  def updateMethodsMetadataFile: IOResult[CmdResult] = {
    val cmd = Cmd("/usr/share/ncf/ncf", "write_all_methods" :: Nil, Map.empty)
    for {
      updateCmd <- RunNuCommand.run(cmd)
      res       <- updateCmd.await
      _         <- ZIO.when(res.code != 0) (Inconsistency(s"An error occured while updating generic methods library with command '${cmd.display}':\n code: ${res.code}\n stderr: ${res.stderr}\n stdout: ${res.stdout}").fail)
      // commit file
      modId     =  ModificationId(uuidGen.newUuid)
      ident     <- personIdentService.getPersonIdentOrDefault(RudderEventActor.name)
      _         <- commitAddFile(modId, ident, toGitPath(methodsFile.toJava), "Saving updated generic methods definition")
    } yield {
      res
    }
  }
  def updateTechniquesMetadataFile : IOResult[CmdResult] = {
    import scala.jdk.CollectionConverters._
    for {
      // Need some environement variable especially PATH towrite techniques
      env       <- IOResult.effect(System.getenv().asScala.toMap)
      cmd       =  Cmd("/usr/share/ncf/ncf", "write_all_techniques" :: Nil, env)
      updateCmd <- RunNuCommand.run(cmd)
      res       <- updateCmd.await
      _         <- ZIO.when(res.code != 0) (Inconsistency(s"An error occured while updating generic methods library with command '${cmd.display}':\n code: ${res.code}\n stderr: ${res.stderr}\n stdout: ${res.stdout}").fail)
    } yield {
      res
    }
  }
}
