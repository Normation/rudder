/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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

/*
 * This file contains generic data about what is a versionned object in
 * Rudder. It's basically a git commitId, but git has several concept behind a commit id
 * that are too low level for us:
 * - a SHA (ObjectId): it's (maybe) a valid id which can point to a tree, a blob, a commit and
 *   certainly many other things;
 * - a Ref: it's a name (tag, branch, HEAD, etc) paired with the ObjectId it references
 *
 */

package com.normation

import java.time.OffsetDateTime

object GitVersion {

  /**
   * The revision of the considered object. A revision is a git name that can be resolved
   * to somewhere in repository:
   * - a SHA commit id,
   * - a branch or tag name,
   * - a relative reference (HEAD~3 etc)
   *
   * Note that "master" actually means "last commit on master" and so is not fixed in time,
   * while "9f35b60b93f01123c13a02db06ccab0a6aa87110" (a specific sha1) is fixed in time.
   *
   * By convention, an empty `value` means `DEFAULT_REV`.
   */
  final case class Revision(value: String) extends AnyVal {
    def toOptionString: Option[String] = value match {
      case DEFAULT_REV.value => None
      case v                 => Some(v)
    }
  }

  /**
   * Meta information are associated with a revision:
   * - date with offset
   * - author
   * - message
   */
  final case class RevisionInfo(
      rev:     Revision,
      date:    OffsetDateTime, // see https://stackoverflow.com/a/78079838
      author:  String,
      message: String
  )

  /**
   * The default name for "last commit on default branch".
   * This is not HEAD, it's actually the last commit on master/main branch.
   *
   * Without more configuration, it's "master"
   * Capitalized so that it can be used in pattern matching
   */
  val DEFAULT_REV: Revision = Revision("default")

  // an empty string is considered as missing version, so DEFAULT_REV.
  object ParseRev {
    def apply(rev: String): Revision = rev match {
      case null | "" | DEFAULT_REV.value => DEFAULT_REV
      case r                             => Revision(r)
    }

    def apply(rev: Option[String]): Revision = rev match {
      case null | None | Some(DEFAULT_REV.value) => DEFAULT_REV
      case Some(r)                               => Revision(r)
    }
  }

  /**
   * A method to parse a standard uid+revision
   */
  def parseUidRev(s: String): Either[String, (String, Revision)] = {
    s.split("\\+").toList match {
      case id :: Nil        => Right((id, GitVersion.DEFAULT_REV))
      case id :: "" :: Nil  => Right((id, GitVersion.DEFAULT_REV))
      case id :: rev :: Nil => Right((id, Revision(rev)))
      case _                => Left(s"Error when parsing '${s}' as a rudder id. At most one '+' is authorized.")
    }
  }

}
