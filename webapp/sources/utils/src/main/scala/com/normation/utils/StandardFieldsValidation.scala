/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
 *************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *************************************************************************************
 */

package com.normation.utils

import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import java.util.regex.Pattern

trait RegexValidation {
  def stringPattern: String
  lazy val pattern = Pattern.compile(stringPattern)
  def isValid(candidate: String): Boolean = pattern.matcher(candidate).matches
}

/*
 * Strict validation of UUIDs
 */
object UuidRegex extends RegexValidation {
  lazy val stringPattern = """[\w]{8}-[\w]{4}-[\w]{4}-[\w]{4}-[\w]{12}"""
}

/*
 * Validation of node UUID is more lax:
 * - we must accept `root`
 * - we used to have user setting their pwn "id". As of rudder 6.0, this should not be supported anymore, but
 *   we will wait for 7.0 to enforce it.
 * - only start by alnum, and continu with [alnum-]
 * - max 50 chars because of DB field size
 */
object NodeIdRegex extends RegexValidation {
  lazy val stringPattern = """([a-zA-Z0-9][a-zA-Z0-9\-]{0,49})"""

  def checkNodeId(uuid: String): PureResult[String] = {
    if (NodeIdRegex.isValid(uuid)) {
      Right(uuid)
    } else {
      Left(
        Inconsistency(
          s"""The node ID '${uuid}' is not valid. It should be lesser than 50 chars and contains alpha-numeric char or dash and no dash in first position (ie matches that regex: ${NodeIdRegex.stringPattern})"""
        )
      )
    }
  }

}

/*
 * For 6.x, we don't impose a max length in contradition to https://datatracker.ietf.org/doc/html/rfc1123#section-2.1
 * We added an exception for "_" that is accepted on Windows (see: https://issues.rudder.io/issues/22186)
 * where each dot-separated segments can be only max 63 chars, and the total length, including dot, 255.
 * But we implement:
 * - must start by alnum
 * - only alnum, dash and dot
 */
object HostnameRegex extends RegexValidation {
  lazy val stringPattern = """([a-zA-Z0-9][a-zA-Z0-9_\-\.]*)"""

  def checkHostname(hostname: String): PureResult[String] = {
    if (HostnameRegex.isValid(hostname)) {
      Right(hostname)
    } else {
      Left(
        Inconsistency(
          s"""The node ID '${hostname}' is not valid. It should only contains alpha-numeric char or dash and no dash in first position (ie matches that regex: ${HostnameRegex.stringPattern})"""
        )
      )
    }
  }
}
