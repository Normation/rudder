package com.normation.rudder.ncf

import better.files.File
import com.normation.errors.IOResult
import com.normation.utils.FileUtils
import java.nio.file.Files
import java.nio.file.Path

trait ResourceWriter {

  def writeResource(
      bytes:            Array[Byte],
      categories:       Seq[String],
      techniqueName:    String,
      techniqueVersion: String,
      resourceName:     String
  ): IOResult[Path]

  def deleteResource(
      categories:       Seq[String],
      techniqueName:    String,
      techniqueVersion: String,
      resourceName:     String
  ): IOResult[Boolean]
}

class ResourceFileSystemWriter(configRepository: String) extends ResourceWriter {

  private def sanitize(
      categories:       Seq[String],
      techniqueName:    String,
      techniqueVersion: String,
      resourceName:     String
  ): IOResult[File] = {
    FileUtils.sanitizePath(
      File(configRepository),
      ("techniques" :: categories.toList) :+ techniqueName :+ techniqueVersion :+ "resources" :+ resourceName
    )
  }

  override def writeResource(
      bytes:            Array[Byte],
      categories:       Seq[String],
      techniqueName:    String,
      techniqueVersion: String,
      resourceName:     String
  ): IOResult[Path] = {

    for {
      sanitized <- sanitize(categories, techniqueName, techniqueVersion, resourceName)
    } yield {
      Files.write(sanitized.path, bytes)
    }
  }

  override def deleteResource(
      categories:       Seq[String],
      techniqueName:    String,
      techniqueVersion: String,
      resourceName:     String
  ): IOResult[Boolean] = {
    for {
      sanitized <- sanitize(categories, techniqueName, techniqueVersion, resourceName)
    } yield {
      Files.deleteIfExists(sanitized.path)
    }
  }
}
