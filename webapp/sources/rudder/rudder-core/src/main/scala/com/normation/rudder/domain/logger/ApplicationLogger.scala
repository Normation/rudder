/*
*************************************************************************************
* Copyright 2012 Normation SAS
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

package com.normation.rudder.domain.logger

import com.normation.NamedZioLogger
import org.slf4j.LoggerFactory
import net.liftweb.common.Logger

/**
 * Applicative log of interest for Rudder ops.
 */
object ApplicationLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("application")
}

/**
 * A logger dedicated to "plugin" information, especially boot info.
 */
object PluginLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("application.plugin")
}

/**
 * A logger dedicated to scheduled jobs and batches
 */
object ScheduledJobLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("scheduledJob")
}

object ScheduledJobLoggerPure extends NamedZioLogger {
  def loggerName = "scheduledJob"
}

/**
 * A logger for new nodes informations
 */
object NodeLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("nodes")
  object PendingNode extends Logger {
    // the logger for information about pending nodes (accept/refuse)
    override protected def _logger = LoggerFactory.getLogger("nodes.pending")
    // the logger for info about what policies will be applied to the new node
    object Policies extends Logger {
      override protected def _logger = LoggerFactory.getLogger("nodes.pending.policies")
    }
  }
}

/*
 * A logger to log information about the JS script eval for directives
 * parameter.s
 */
object JsDirectiveParamLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("jsDirectiveParam")
}

object JsDirectiveParamLoggerPure extends NamedZioLogger {
  def loggerName = "jsDirectiveParam"
}


object GenerationLoggerPure extends NamedZioLogger {
  def loggerName = "policy-generation"
}
