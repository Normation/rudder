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
import net.liftweb.common.Logger
import org.slf4j
import org.slf4j.LoggerFactory

/**
 * Applicative log of interest for Rudder ops.
 */
object ApplicationLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("application")

  object Properties extends Logger {
    override protected def _logger: slf4j.Logger = LoggerFactory.getLogger("application.properties")
  }
}

object ApplicationLoggerPure extends NamedZioLogger {
  parent =>
  def loggerName = "application"

  object Archive extends NamedZioLogger {
    def loggerName = parent.loggerName + ".archive"
  }

  object Plugin extends NamedZioLogger {
    def loggerName = parent.loggerName + ".plugin"
  }

  object Authz extends NamedZioLogger {
    def loggerName = parent.loggerName + ".authorization"
  }
}

object ApiLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("api-processing")
}

object ApiLoggerPure extends NamedZioLogger {
  def loggerName = "api-processing"

  object Metrics extends NamedZioLogger {
    def loggerName = "api-processing.metrics"
  }
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
  override protected def _logger = LoggerFactory.getLogger("scheduled.job")
}

object ScheduledJobLoggerPure extends NamedZioLogger {
  parent =>
  def loggerName = "scheduled.job"

  object metrics extends NamedZioLogger {
    def loggerName: String = parent.loggerName + ".metrics"
  }
}

object DynamicGroupLoggerPure extends NamedZioLogger {
  override def loggerName: String = "dynamic-group"

  object Timing extends NamedZioLogger {
    override def loggerName: String = DynamicGroupLoggerPure.loggerName + ".timing"
  }
}

/**
 * A logger for new nodes information
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
 * Log things related to global parameter, group and node properties interpolation and
 * policy generation (ie: not if a problem with global param API, but something like overriding is
 * broken for it, or property engine is broken
 */
object NodePropertiesLoggerPure extends NamedZioLogger {
  override def loggerName: String = "node.properties"
}

object NodeLoggerPure extends NamedZioLogger { parent =>
  def loggerName = "nodes"
  object Delete extends NamedZioLogger {
    def loggerName: String = parent.loggerName + ".delete"
  }

  object Cache extends NamedZioLogger {
    def loggerName: String = parent.loggerName + ".cache"
  }

  object Metrics extends NamedZioLogger {
    def loggerName: String = parent.loggerName + ".metrics"
  }

  object Details extends NamedZioLogger {
    def loggerName: String = parent.loggerName + ".details"
  }

  object Security extends NamedZioLogger {
    def loggerName: String = parent.loggerName + ".security"
  }

  object PendingNode extends NamedZioLogger {
    // the logger for information about pending nodes (accept/refuse)
    def loggerName: String = parent.loggerName + ".pending"
    // the logger for info about what policies will be applied to the new node
    object Policies extends NamedZioLogger {
      def loggerName: String = parent.loggerName + ".policies"
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

object TechniqueReaderLoggerPure extends NamedZioLogger {
  def loggerName = "techniques.reader"
}

/*
 * Things related to transforming technique from editor
 * to final agent specific files
 */
object TechniqueWriterLoggerPure extends NamedZioLogger {
  def loggerName = "techniques.writer"

}

object RuddercLogger extends NamedZioLogger {
  override def loggerName: String = "techniques.writer.rudderc" // since it's not rudderc logs, but webapp logs about rudderc
}

/**
 * Logger about change request and other workflow thing.
 */
object ChangeRequestLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("changeRequest")
}

/**
 * Logger used for historization of object names by `HistorizationService`
 */
object HistorizationLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("historization")
}

object GitArchiveLoggerPure extends NamedZioLogger {
  override def loggerName: String = "git-policy-archive"
}

object ConfigurationLoggerPure extends NamedZioLogger {
  def loggerName = "configuration"

  object revision extends NamedZioLogger {
    def loggerName = "configuration.revision"
  }
}

object GitArchiveLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("git-policy-archive")
}

object ComplianceLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("compliance")
}

object ReportLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("report")

  object Cache extends Logger {
    override protected def _logger = LoggerFactory.getLogger("report.cache")
  }
}

object FactQueryProcessorLoggerPure extends NamedZioLogger {
  override def loggerName: String = "query.node-fact"

  object Metrics extends NamedZioLogger {
    override def loggerName: String = FactQueryProcessorLoggerPure.loggerName + ".metrics"
  }
}

object ReportLoggerPure extends NamedZioLogger {
  override def loggerName: String = "report"

  object Changes extends NamedZioLogger {
    override def loggerName: String = "report.changes"
  }
  object Cache   extends NamedZioLogger {
    override def loggerName: String = "report.cache"
  }
}

object GitRepositoryLogger extends NamedZioLogger() {
  def loggerName = "git-repository"
}
