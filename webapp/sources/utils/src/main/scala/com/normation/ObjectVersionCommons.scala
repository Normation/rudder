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


final object GitVersion {

  /**
   * A git name that can be resolve to somewhere in repository:
   * - a SHA commit id,
   * - a branch or tag name,
   * - a relative reference (HEAD~3 etc)
   *
   * Note that "master" actually means "last commit on master" and so is not fixed in time,
   * while "9f35b60b93f01123c13a02db06ccab0a6aa87110" (a specific sha1) is fixed in time.
   *
   * Naming idea:
   * - ObjectId:
   *   Git.ObjectId makes sense, but ObjectId is quite generic and non-informative. In the context of
   *   Rudder, it can even be misleading: object id is likely understood as directive/rule uuid
   *
   * - GitId:
   *   make it clear that we talk about a Git thing. It loose the idea that for rudder, it's about
   *   versioning/revision. And it can be misleading as our "git id" are not exactly what Git call
   *   an id (ie not only a SHA)
   *
   * - CommitId:
   *   Has the notion of revision and is tigly bound to "git" (or source control) in developer mind -
   *   but perhaps confusing because it's not always a real commit id (for ex: "master").
   *
   * - RevisionId:
   *   it allows to not explicitly talk about git in the context of rudder objects, which
   *   is perhaps good (avoid talking about implementation and more about what the thing is in the
   *   context of Rudder: Rule(uuid, revId). But perhaps that just add a layer for nothing, bluring
   *   concepts. And RevisionId is quite long.
   *
   *   I will go for "RevId" for now, perhaps need to change latter on.
   */
  final case class RevId(value: String) extends AnyVal


  /**
   * The default name for "last commit on default branch"
   */
  val defaultRev = RevId("head")

  // an empty string is considered as missing version, so defaultRev.
  object ParseRev {
    def apply(revId: String): Option[RevId] = revId match {
      case null | "" | defaultRev.value => None
      case r                            => Some(RevId(r))
    }

    def apply(revId: Option[String]): Option[RevId] = revId match {
      case null | None                       => None
      case Some("") | Some(defaultRev.value) => None
      case Some(r)                           => Some(RevId(r))
    }
  }

}

