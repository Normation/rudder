package com.normation.rudder.ncf

import better.files.File
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.RunNuCommand
import com.normation.rudder.rest.RestExtractorService
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.parse
import zio.ZIO

class TechniqueReader(
  restExtractor  : RestExtractorService
) {


  import zio.syntax._
  import better.files.File._
  val configuration_repository = root / "var" / "rudder" / "configuration-repository"
  val methodsFile = configuration_repository / "ncf" / "generic_methods.json"
  def getAllTechniqueFiles(currentPath : File): List[File]= {
    val (subdirs, files) = currentPath.children.partition(_.isDirectory)
    val techniqueFilePath = currentPath / "technique.json"
    val checkSubdirs = subdirs.flatMap(getAllTechniqueFiles).toList
    if (techniqueFilePath.exists) {
      techniqueFilePath :: checkSubdirs
    } else {
      checkSubdirs
    }
  }
  def readTechniquesMetadataFile: IOResult[List[Technique]] = {
    for {
      methods <- readMethodsMetadataFile
      techniques <- ZIO.foreach(getAllTechniqueFiles(configuration_repository / "techniques"))(
        techniqueFile =>
            restExtractor.extractNcfTechnique(parse(techniqueFile.contentAsString), methods, false) match {
              case Full(m) => m.succeed
              case eb: EmptyBox =>
                val fail = eb ?~! s"An Error occured while extracting data from techniques ncf API"
                Inconsistency(fail.messageChain).fail
            })
    } yield {
      techniques
    }
  }

  def readMethodsMetadataFile : IOResult[Map[BundleName, GenericMethod]] = {
    for {
      genericMethodContent <- IOResult.effect(s"error while reading ${methodsFile.pathAsString}")(methodsFile.contentAsString)
      methods <- parse(genericMethodContent) match {
        case JObject(fields) =>
          restExtractor.extractGenericMethod(JArray(fields.map(_.value))).map(_.map(m => (m.id, m)).toMap) match {
            case Full(m) => m.succeed
            case eb: EmptyBox =>
              val fail = eb ?~! s"An Error occured while extracting data from generic methods ncf API"
              Inconsistency(fail.messageChain).fail
          }
        case a => Inconsistency(s"Could not extract methods from ncf api, expecting an object got: ${a}").fail
      }
    } yield {
      methods
    }
  }

  def updateMethodsMetadataFile = {
    for {
      update <- RunNuCommand.run(Cmd("/usr/share/ncf/ncf", "write_all_methods" :: Nil, Map.empty))
      methods <- readMethodsMetadataFile
    } yield {
      methods
    }
  }
  def updateTechniquesMetadataFile = {
    for {
      update <- RunNuCommand.run(Cmd("/usr/share/ncf/ncf", "write_all_techniques" :: Nil, Map.empty))
      techniques <- readTechniquesMetadataFile
    } yield {
      techniques
    }
  }
}
