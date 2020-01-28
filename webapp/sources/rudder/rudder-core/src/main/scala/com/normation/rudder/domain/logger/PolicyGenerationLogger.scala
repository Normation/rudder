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
package com.normation.rudder.domain.logger

import com.normation.NamedZioLogger
import org.slf4j.LoggerFactory
import net.liftweb.common.Logger

/**
 * Applicative log of for policy generation
 */
object PolicyGenerationLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("policy.generation")

  object expectedReports extends Logger {
    override protected def _logger = LoggerFactory.getLogger("policy.generation.expected_reports")
  }

  object update extends Logger {
    override protected def _logger = LoggerFactory.getLogger("policy.generation.update")
  }

  object timing extends Logger {
    override protected def _logger = LoggerFactory.getLogger("policy.generation.timing")
    object buildNodeConfig extends Logger {
      override protected def _logger = LoggerFactory.getLogger("policy.generation.timing.buildNodeConfig")
    }
  }

}

object PolicyGenerationLoggerPure extends NamedZioLogger {
  override def loggerName  = "policy.generation"

  object expectedReports extends NamedZioLogger {
    override def loggerName = "policy.generation.expected_reports"
  }

  object timing extends NamedZioLogger {
    override def loggerName: String = "policy.generation.timing"
    object buildNodeConfig extends NamedZioLogger {
      override def loggerName: String = "policy.generation.timing.buildNodeConfig"
    }
    object hooks extends NamedZioLogger {
      override def loggerName: String = "policy.generation.timing.hooks"
    }
  }

}


