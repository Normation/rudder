/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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
package net.liftweb.http

import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.users.CurrentUser
import net.liftweb.common.Loggable
import net.liftweb.http.DispatchSnippet

/*
 * Secured version of a dispatch snippet with respect to tenants, by providing a query context.
 */
trait SecureDispatchSnippet extends DispatchSnippet { self: Loggable =>

  /**
   * Dispatch function, with a QueryContext already provided
   * Implementations can just use it, ?=> here is just a syntax sugar
   */
  def secureDispatch: QueryContext ?=> DispatchIt

  override def dispatch: DispatchIt = {
    CurrentUser.queryContext.withQCOr(loggedInsecureDispatch)(secureDispatch)
  }

  private def loggedInsecureDispatch: DispatchIt = {
    logger.warn(s"Snippet can't be accessed in current security context (user is not authenticated)")
    PartialFunction.empty
  }

}
