package com.normation.rudder.web.services

import com.normation.errors.IOResult

import java.nio.file.Path

// todo reuse RudderEngineProperty ?
case class SecretVaultEntry(val name: String, val value: String, val engine: String)

class SecretVaultService(
  db: Path
) {

  def getSecrets: IOResult[List[SecretVaultService]] = {
    for {

    } yield {

    }
  }

  def addSecret(secret: SecretVaultEntry): IOResult[Unit] = {
    for {

    } yield {

    }
  }

  def deleteSecret(secret: SecretVaultEntry): IOResult[Unit] = {
    for {

    } yield {

    }
  }

  def updateSecret(secret: SecretVaultEntry): IOResult[Unit] = {
    for {

    } yield {

    }
  }


}
