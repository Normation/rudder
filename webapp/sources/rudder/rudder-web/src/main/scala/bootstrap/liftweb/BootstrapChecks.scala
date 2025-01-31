/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

import com.normation.NamedZioLogger
import com.normation.errors.IOResult
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import jakarta.servlet.UnavailableException
import zio.*

/**
 *
 * Interface of the pipeline for action to execute
 * at bootstrap.
 *
 *
 * Implementation may really be executed when the application
 * is launched, so be careful with the time taken by them.
 *
 * On the other hand, for async bootstraps checks, don't expect
 * order in there execution.
 */
trait BootstrapChecks {

  def description: String

  @throws(classOf[UnavailableException])
  def checks(): Unit

}

object BootstrapLogger extends NamedZioLogger {
  final override def loggerName: String = "bootchecks"

  object Early extends NamedZioLogger {
    final override def loggerName: String = "bootchecks.early"

    object DB extends NamedZioLogger {
      final override def loggerName: String = "bootchecks.early.db"
    }

    object LDAP extends NamedZioLogger {
      final override def loggerName: String = "bootchecks.early.ldap"
    }
  }
}

class SequentialImmediateBootStrapChecks(sequenceName: String, logger: NamedZioLogger, checkActions: BootstrapChecks*)
    extends BootstrapChecks {

  override val description = "Sequence of bootstrap checks"

  // only execute once when at class instantiation.

  // keep that method for easy external reference to the class, so that in case of lazy val, the instance
  // actually get instantiated
  @throws(classOf[UnavailableException])
  override def checks(): Unit = {
    pureChecks().runNow
  }

  protected def pureChecks(): UIO[Unit] = {
    for {
      _ <- logger.info(s"Starting bootchecks for ${sequenceName}")
      _ <- ZIO.foreach(checkActions.zipWithIndex) {
             case (check, i) =>
               val msg = s"[#${i}] ${check.description}"
               for {
                 _ <- logger.info(msg)
                 r <- IOResult.attempt(check.checks()).catchAll(err => logger.error(s"${msg}: ${err.fullMsg}")).timed
                 _ <- logger.debug(s"${msg}: OK in [${DateFormaterService.formatJavaDuration(r._1)}]")
               } yield ()
           }
    } yield ()
  }
}

/*
 * Implementation that only execute once the given list of checks, immediately when the class
 * is instantiated. Used for the most early check, so that we can reference "mostEarlyCheck.check"
 * where ever it looks like a root of our instantiation dependency graphe.
 */
class OnceBootstrapChecks(sequenceName: String, logger: NamedZioLogger, checkActions: BootstrapChecks*)
    extends SequentialImmediateBootStrapChecks(sequenceName, logger, checkActions*) {

  // execute once when instantiated
  super.checks()

  // does nothing for checks
  override def checks(): Unit = {}

  // initialize is less strange than "checks"
  def initialize(): Unit = {}
}
