/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.git

import org.eclipse.jgit.lib.PersonIdent

/**
 * Container for a Git Path.
 * Notice: a GIT path should be well formed
 * (never starts with a "/", not heading nor trailing
 * empty characters), but this is not enforce by that class.
 */
final case class GitPath(value: String) extends AnyVal

// TODO: merge with revision

/**
 * A git commit string character, the SHA-1 hash that can be
 * use in git command line with git checkout, git show, etc.
 */
final case class GitCommitId(value: String) extends AnyVal

/**
 * A Git archive ID is a couple of the path on witch the archive was made,
 * and the commit that reference the tag.
 * Note that the commit ID is stable in time, but the path is just an
 * indication, especially if its 'master' (a branch path is more likely to be
 * a little more stable).
 */
final case class GitArchiveId(path: GitPath, commit: GitCommitId, commiter: PersonIdent)
