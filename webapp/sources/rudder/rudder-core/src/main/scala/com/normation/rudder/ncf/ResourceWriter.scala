package com.normation.rudder.ncf

import better.files.File
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors.IOResult
import com.normation.utils.FileUtils

trait ResourceFileSystemWriter {

  def writeResource(categories: Seq[String]): IOResult[Boolean]

  def deleteResource(categories: Seq[String]): IOResult[Boolean]

}

class ResourceFileSystemWriterImpl extends ResourceFileSystemWriter {

  override def writeResource(categories: Seq[String], techniqueName:String, techniqueVersion: TechniqueVersion, resourceName: String): IOResult[Boolean] = {

    import java.nio.file.Files

    val configRepository = "/var/rudder/configuration-repository"
    for {
      sanitized <- FileUtils.sanitizePath(
        File(configRepository),
        ("techniques" :: categories.toList) :+ techniqueName :+ techniqueVersion :+ "resources" :+ resourceName
      )
    } yield {
      Files.deleteIfExists(sanitized.path)
    }
  }
}

  override def deleteResource(categories: Seq[String]): IOResult[Boolean] = ???
}