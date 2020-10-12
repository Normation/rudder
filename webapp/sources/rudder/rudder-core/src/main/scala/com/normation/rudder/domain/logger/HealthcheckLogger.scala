package com.normation.rudder.domain.logger

import com.normation.NamedZioLogger

object HealthcheckLoggerPure extends NamedZioLogger {
  override def loggerName  = "healthcheck"
}
