/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

package bootstrap.liftweb

import com.normation.rudder.domain.logger.PluginLogger

/**
 * This file contains data structure that choose the level of rights Rudder understand for a user. 
 * Without plugin, it only knows about admin / no rights. 
 * With user management plugins, it knows about roles and fine permissions.
 */

/**
 * This is the class that defines the user management level.
 * Without the plugin, by default only "admin" role is know.
 * A user with an unknown role has no rights.
 */
trait UserAuthorisationLevel {
  def userAuthEnabled: Boolean
  def name:            String
}

// and default implementation is: no
class DefaultUserAuthorisationLevel() extends UserAuthorisationLevel {
  // Alternative level provider
  private[this] var level: Option[UserAuthorisationLevel] = None

  def overrideLevel(l: UserAuthorisationLevel): Unit    = {
    PluginLogger.info(s"Update User Authorisations level to '${l.name}'")
    level = Some(l)
  }
  override def userAuthEnabled:                 Boolean = level.map(_.userAuthEnabled).getOrElse(false)

  override def name: String = level.map(_.name).getOrElse("Default implementation (only 'admin' right)")
}
