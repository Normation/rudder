package com.normation.rudder.ncf

import better.files.File
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.RunNuCommand
import com.normation.rudder.rest.RestExtractorService
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.parse
import zio.ZIO._
import zio.syntax._
import com.normation.errors._

class TechniqueReader(
  restExtractor  : RestExtractorService
) {
  import better.files.File.root
  val configuration_repository = root / "var" / "rudder" / "configuration-repository"
  val methodsFile = configuration_repository / "ncf" / "generic_methods.json"
  def getAllTechniqueFiles(currentPath : File): IOResult[List[File]]= {
    import com.normation.errors._
    for {
      subdirs <- IOResult.effect (s"error when getting subdirectories of ${currentPath.pathAsString}") (currentPath.children.partition(_.isDirectory)._1.toList)

      checkSubdirs  <- foreach(subdirs)(getAllTechniqueFiles).map(_.flatten)
      techniqueFilePath : File = currentPath / "technique.json"
      result <- IOResult.effect(
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
      methods <- readMethodsMetadataFile
      techniqueFiles <- getAllTechniqueFiles(configuration_repository / "techniques")
      techniques <- foreach(techniqueFiles)(
                      file =>
                        restExtractor.extractNcfTechnique(parse(file.contentAsString), methods, false).toIO
                          .chainError("An Error occured while extracting data from techniques ncf API")
                    )
    } yield {
      techniques
    }
  }

  def readMethodsMetadataFile : IOResult[Map[BundleName, GenericMethod]] = {

    for {
      genericMethodContent <- IOResult.effect(s"error while reading ${methodsFile.pathAsString}")(methodsFile.contentAsString)
      methods <- parse(genericMethodContent) match {
        case JObject(fields) =>
          restExtractor.extractGenericMethod(JArray(fields.map(_.value))).map(_.map(m => (m.id, m)).toMap).toIO
          .chainError(s"An Error occured while extracting data from generic methods ncf API")

        case a => Inconsistency(s"Could not extract methods from ncf api, expecting an object got: ${a}").fail
      }
    } yield {
      methods
    }
  }

  def updateMethodsMetadataFile : IOResult[Map[BundleName, GenericMethod]] = {
    for {
      update <- RunNuCommand.run(Cmd("/usr/share/ncf/ncf", "write_all_methods" :: Nil, Map.empty))
      methods <- readMethodsMetadataFile
    } yield {
      methods
    }
  }
  def updateTechniquesMetadataFile : IOResult[List[Technique]] = {
    for {
      update <- RunNuCommand.run(Cmd("/usr/share/ncf/ncf", "write_all_techniques" :: Nil, Map.empty))
      techniques <- readTechniquesMetadataFile
    } yield {
      techniques
    }
  }
}
