package com.normation.rudder.ncf

import better.files._
import com.normation.errors._
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.git.GitConfigItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.hooks.RunNuCommand
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.repository.xml.XmlArchiverUtils
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.StringUuidGenerator
import com.normation.zio._
import java.time.Instant
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.parse
import zio.Ref
import zio.ZIO
import zio.ZIO._
import zio.syntax._

class TechniqueReader(
    restExtractor:                          RestExtractorService,
    uuidGen:                                StringUuidGenerator,
    personIdentService:                     PersonIdentService,
    override val gitRepo:                   GitRepositoryProvider,
    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    override val encoding:                  String,
    override val groupOwner:                String,
    techniqueSerializer:                    TechniqueSerializer,
    yamlTechniqueSerializer:                YamlTechniqueSerializer
) extends GitConfigItemRepository with XmlArchiverUtils {
  override val relativePath: String = "ncf"
  val configuration_repository = gitRepo.rootDirectory
  val ncfRootDir               = configuration_repository / relativePath
  val methodsFile              = ncfRootDir / "generic_methods.json"

  def getAllTechniqueFiles(currentPath: File): IOResult[List[File]]                                              = {
    import com.normation.errors._
    for {
      subdirs      <- IOResult.attempt(s"error when getting subdirectories of ${currentPath.pathAsString}")(
                        currentPath.children.partition(_.isDirectory)._1.toList
                      )
      checkSubdirs <- foreach(subdirs)(getAllTechniqueFiles).map(_.flatten)
      techniqueFilePath: File = currentPath / "technique.yml"
      result <- IOResult.attempt(
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
  def readTechniquesMetadataFile:              IOResult[(List[EditorTechnique], Map[BundleName, GenericMethod])] = {
    import zio.json.yaml._
    import yamlTechniqueSerializer._
    for {
      methods        <- getMethodsMetadata
      techniqueFiles <- getAllTechniqueFiles(configuration_repository / "techniques")
      techniques     <- foreach(techniqueFiles)(file => {

                          file.contentAsString
                            .fromYaml[EditorTechnique]
                            .toIO
                            .chainError("An Error occurred while extracting data from techniques ncf API")
                        })
    } yield {
      (techniques, methods)
    }
  }

  private[this] val methodsCache = Ref.Synchronized.make((Instant.EPOCH, Map[BundleName, GenericMethod]())).runNow
  def getMethodsMetadata: IOResult[Map[BundleName, GenericMethod]] = {
    for {
      cache <- methodsCache.updateAndGetZIO(readMethodsMetadataFile)
    } yield {
      cache._2
    }
  }
  private[this] def readMethodsMetadataFile(
      cache: (Instant, Map[BundleName, GenericMethod])
  ): IOResult[(Instant, Map[BundleName, GenericMethod])] = {
    if (methodsFile.exists()) {
      for {
        methodsFileModifiedTime <- IOResult.attempt(methodsFile.lastModifiedTime())
        methods                 <-
          if (methodsFileModifiedTime.isAfter(cache._1)) {
            for {
              genericMethodContent <-
                IOResult.attempt(s"error while reading ${methodsFile.pathAsString}")(methodsFile.contentAsString)
              parsedMethods        <- parse(genericMethodContent) match {
                                        case JObject(fields) =>
                                          restExtractor
                                            .extractGenericMethod(JArray(fields.map(_.value)))
                                            .map(_.map(m => (m.id, m)).toMap)
                                            .toIO
                                            .chainError(s"An Error occurred while extracting data from generic methods ncf API")

                                        case a =>
                                          Inconsistency(s"Could not extract methods from ncf api, expecting an object got: ${a}").fail
                                      }
              now                  <- currentTimeMillis
            } yield {
              (Instant.ofEpochMilli(now), parsedMethods)
            }
          } else {
            cache.succeed
          }
      } yield {
        methods
      }
    } else {
      updateMethodsMetadataFile *> readMethodsMetadataFile(cache)
    }
  }

  def updateMethodsMetadataFile: IOResult[CmdResult] = {
    // Comes with the rudder-server packages
    val systemLib = "/usr/share/ncf/tree/30_generic_methods"
    // User-defined methods + plugin methods (including windows)
    val localLib  = "/var/rudder/configuration-repository/ncf/30_generic_methods"

    val methodLibs    = if (File(localLib).exists) {
      systemLib :: localLib :: Nil
    } else {
      systemLib :: Nil
    }
    val ruddercBin    = "/opt/rudder/bin/rudderc"
    val ruddercParams = "lib" :: "--format" :: "json" :: "--stdout" :: Nil
    val ruddercLibs   = methodLibs.flatMap(l => "--library" :: l :: Nil)
    // We want everything in configuration repository to belong to the "rudder" group
    val groupOwner    = "rudder"

    val cmd = Cmd(ruddercBin, ruddercParams ::: ruddercLibs, Map.empty)
    for {
      updateCmd <- RunNuCommand.run(cmd)
      res       <- updateCmd.await
      _         <-
        ZIO.when(res.code != 0)(
          Inconsistency(
            s"An error occurred while updating generic methods library with command '${cmd.display}':\n code: ${res.code}\n stderr: ${res.stderr}\n stdout: ${res.stdout}"
          ).fail
        )
      // write file
      _         <- IOResult.attempt(methodsFile.parent.createDirectories())
      _         <- IOResult.attempt(methodsFile.parent.setGroup(groupOwner))
      _         <- IOResult.attempt(methodsFile.writeText(res.stdout))
      _         <- IOResult.attempt(methodsFile.setGroup(groupOwner))
      // commit file
      modId      = ModificationId(uuidGen.newUuid)
      ident     <- personIdentService.getPersonIdentOrDefault(RudderEventActor.name)
      _         <- commitAddFileWithModId(modId, ident, toGitPath(methodsFile.toJava), "Saving updated generic methods definition")
    } yield {
      res
    }
  }
}
