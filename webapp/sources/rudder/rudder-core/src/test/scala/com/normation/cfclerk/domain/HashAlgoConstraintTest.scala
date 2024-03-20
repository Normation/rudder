package com.normation.cfclerk.domain

import com.normation.utils.EnumLaws
import zio.test.Spec
import zio.test.ZIOSpecDefault

object HashAlgoConstraintTest extends ZIOSpecDefault {

  private val validNames = Seq(
    "PLAIN",
    "PRE-HASHED",
    "MD5",
    "SHA1",
    "SHA256",
    "SHA512",
    "LINUX-SHADOW-MD5",
    "LINUX-SHADOW-SHA256",
    "LINUX-SHADOW-SHA512",
    "AIX-SMD5",
    "AIX-SSHA256",
    "AIX-SSHA512",
    "UNIX-CRYPT-DES"
  )

  val spec: Spec[Any, Nothing] = suite("HashAlgoConstraint")(
    EnumLaws.laws(HashAlgoConstraint, validNames)
  )

}
