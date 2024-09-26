package bootstrap.liftweb.checks.action

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.rudder.ncf.TechniqueCompilationStatusSyncService
import com.normation.zio.UnsafeRun

/**
  * Reload the global technique compilation status at startup.
  * It is done asynchronously because errors (e.g. on a yml file in the filesystem)
  * are not supposed to prevent Rudder startup.
  */
class CheckTechniqueCompilationStatus(
    techniqueCompilationStatusService: TechniqueCompilationStatusSyncService
) extends BootstrapChecks {

  override def description: String = "Check for technique compilation errors"

  override def checks(): Unit = {
    techniqueCompilationStatusService
      .getUpdateAndSync()
      .catchAll(err => BootstrapLogger.error(s"Error when trying to check technique compilation errors: ${err.fullMsg}"))
      .forkDaemon
      .runNow
  }
}
