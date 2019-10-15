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

import org.slf4j.LoggerFactory

import com.normation.rudder.domain.Constants.XML_CURRENT_FILE_FORMAT
import com.normation.rudder.migration.MigrableEntity

import net.liftweb.common.Failure
import net.liftweb.common.Logger


final case class MigrationLogger(
    goal : Int = XML_CURRENT_FILE_FORMAT
) extends Logger {
  override protected def _logger = LoggerFactory.getLogger("migration")

  val defaultErrorLogger : Failure => Unit = { f =>
    _logger.error(f.messageChain)
    f.rootExceptionCause.foreach { ex =>
      _logger.error("Root exception was:", ex)
    }
  }
  val defaultSuccessLogger : Seq[MigrableEntity] => Unit = { seq =>
    if(_logger.isTraceEnabled) {
      seq.foreach { log =>
        _logger.trace("Migrating eventlog to format %s, id: ".format(goal) + log.id)
      }
    }
    _logger.debug("Successfully migrated %s eventlog to format %s".format(seq.size,goal))
  }
}
