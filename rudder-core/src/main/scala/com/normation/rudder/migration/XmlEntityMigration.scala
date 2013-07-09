package com.normation.rudder.migration

import scala.xml.Elem
import net.liftweb.common._
import com.normation.rudder.domain.logger.MigrationLogger
import scala.xml.NodeSeq
import net.liftweb.util.Helpers.tryo
import org.springframework.jdbc.core.JdbcTemplate
import scala.collection.JavaConverters.asScalaBufferConverter
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import com.normation.utils.Control._
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.util.Calendar

/**
 * specify from/to version
 */
trait XmlFileFormatMigration {

  def fromVersion: Int
  def toVersion  : Int

  def logger = MigrationLogger(toVersion)

  def errorLogger: Failure => Unit = logger.defaultErrorLogger
  def successLogger: Seq[MigrableEntity] => Unit = logger.defaultSuccessLogger

}



/**
 * This class manage the hight level migration process: read if a
 * migration is required in the MigrationEventLog datatable, launch
 * the migration process, write migration result.
 * The actual migration of event logs is delegated to a lit of
 * batchMigrators.
 * If too old fileFormat are found, their migration is delegated to older
 * ControlXmlFileFormatMigration
 */
trait ControlXmlFileFormatMigration extends XmlFileFormatMigration {

  def migrationEventLogRepository: MigrationEventLogRepository
  def batchMigrators             : Seq[BatchElementMigration[_]]
  def previousMigrationController: Option[ControlXmlFileFormatMigration]

  def migrate() : Box[MigrationStatus] = {
    /*
     * test is we have to migrate, and execute migration
     */
    migrationEventLogRepository.getLastDetectionLine match {
      case None =>
        logger.info("No migration detected by migration script (table '%s' is empty or does not exists)".
            format(MigrationEventLogTable.migrationEventLog.name)
        )
        Full(NoMigrationRequested)

      /*
       * we only have to deal with the migration if:
       * - fileFormat is == fromVersion AND (
       *   - migrationEndTime is not set OR
       *   - migrationFileFormat == fromVersion
       * )
       */

      //new migration
      case Some(status@SerializedMigrationEventLog(
          _
        , detectedFileFormat
        , migrationStartTime
        , migrationEndTime @ None
        , _
        , _
      )) if(detectedFileFormat == fromVersion) =>
        /*
         * here, simply start a migration for the first time (if migrationStartTime is None)
         * or continue a previously started migration (but interrupted ?)
         */
        if(migrationStartTime.isEmpty) {
          migrationEventLogRepository.setMigrationStartTime(status.id, new Timestamp(Calendar.getInstance.getTime.getTime))
        }

        val migrationResults = batchMigrators.map { migrator =>
          logger.info(s"Start migration of ${migrator.elementName} from format '${fromVersion}' to '${toVersion}'")

          migrator.process() match {
            case Full(i) =>
              logger.info(s"Migration of ${migrator.elementName} fileFormat from '${fromVersion}' to '${toVersion}' done, ${i} EventLogs migrated")
              Full(MigrationSuccess(i))

            case eb:EmptyBox =>
              val e = (eb ?~! s"Could not correctly finish the migration for ${migrator.elementName} fileFormat from '${fromVersion}' to '${toVersion}'. Check logs for errors. The process can be trigger by restarting the application.")
              logger.error(e)
              e
          }
        }


         boxSequence(migrationResults) match {
          case Full(seq) =>
            val numberMigrated = seq.collect { case MigrationSuccess(i) => i }.sum
            migrationEventLogRepository.setMigrationFileFormat(status.id, toVersion, new Timestamp(Calendar.getInstance.getTime.getTime))
            logger.info(s"Completed migration to file format '${toVersion}', ${numberMigrated} records migrated")
            Full(MigrationSuccess(numberMigrated))
          case eb:EmptyBox => eb
        }

      //a past migration was done, but the final format is not the one we want
      case Some(x@SerializedMigrationEventLog(
          _
        , _
        , _
        , Some(endTime)
        , Some(migrationFileFormat)
        , _
      )) if(migrationFileFormat == fromVersion) =>
        //create a new status line with detected format = migrationFileFormat,
        //and a description to say why we recurse
        migrationEventLogRepository.createNewStatusLine(migrationFileFormat, Some(s"Found a post-migration fileFormat='${migrationFileFormat}': update"))
        this.migrate()

          // lower file format found, send to parent)
      case Some(status@SerializedMigrationEventLog(
          _
        , detectedFileFormat
        , migrationStartTime
        , migrationEndTime @ None
        , _
        , _
      )) if(detectedFileFormat < fromVersion) =>


        logger.info("Found and older migration to do")
        previousMigrationController match {
          case None =>
            logger.info(s"The detected format ${detectedFileFormat} is no more supported")
            Full(MigrationVersionNotSupported)

          case Some(migrator) => migrator.migrate() match{
            case Full(MigrationSuccess(i)) =>
                logger.info("Older migration completed, relaunch migration")
                this.migrate()
            case eb:EmptyBox =>
                val e = (eb ?~! s"Older migration failed, Could not correctly finish the migration from EventLog fileFormat from '${fromVersion}' to '${toVersion}'. Check logs for errors. The process can be trigger by restarting the application")
                logger.error(e)
                e
            case _ =>
                logger.info("Older migration completed, relaunch migration")
                this.migrate()
            }
        }

      //a past migration was done, but the final format is not the one we want
      case Some(x@SerializedMigrationEventLog(
          _
        , _
        , _
        , Some(endTime)
        , Some(migrationFileFormat)
        , _
      )) if(migrationFileFormat < fromVersion) =>
        //create a new status line with detected format = migrationFileFormat,
        //and a description to say why we recurse
        previousMigrationController.foreach { migrator =>
          migrator.migrate()
        }
        this.migrate()


      //other case: does nothing
      case Some(x) =>
        logger.debug(s"Migration of EventLog from format '${fromVersion}' to '${toVersion}': nothing to do")
        Full(MigrationVersionNotHandledHere)
    }
  }

}

/**
 * Migrate one XML data
 */
trait IndividualElementMigration[T <: MigrableEntity] {
  def migrate(element:T) : Box[T]
}

/**
 * A trait that explain how to migrate one type of data in data base
 * (it delelegates the actual XML migration to someone else)
 */
trait BatchElementMigration[T <: MigrableEntity] extends XmlFileFormatMigration {

  def jdbcTemplate: JdbcTemplate
  def individualMigration: IndividualElementMigration[T]
  def rowMapper: RowMapper[T]

  def selectAllSqlRequest: String
  def batchSize : Int

  //human readable name of elements to migrate, for logs
  def elementName: String

  /**
   * retrieve all change request to migrate.
   * By default, we get ALL event log and look
   * if one (at least) has a file format version
   * less that the toVersion.
   */
  def findAll : Box[Seq[T]] = {

    //check if the event must be migrated
    def needMigration(xml:NodeSeq) : Boolean = (
    try {
           (xml \\ "@fileFormat" exists { _.text.toInt == fromVersion })
         } catch {
           case e:NumberFormatException => false
         }
    )

    tryo(
        jdbcTemplate.query(selectAllSqlRequest, rowMapper).asScala
       .filter(log => needMigration(log.data))
    )
  }

  /**
   * Save a list of change
   */
  protected def save(logs:Seq[T]) : Box[Seq[T]]

  /**
   * General algorithm: get all change request to migrate,
   * then process and save them.
   * Return the number of change request migrated
   */
  def process() : Box[Int] = {
    for {
      elts     <- findAll
      migrated <- saveResults(
                      migrate(elts, errorLogger)
                    , save = save
                    , successLogger = successLogger
                    , batchSize = batchSize
                  )
    } yield {
      migrated
    }
  }

  private[this] def migrate(
      elements     : Seq[T]
    , errorLogger  : Failure => Unit
  ) : Seq[T] = {
    elements.flatMap { elt =>
      individualMigration.migrate(elt) match {
        case eb:EmptyBox =>
          errorLogger(eb ?~! s"Error when trying to migrate change request with id '${elt.id}'")
          None
        case Full(m)     => Some(m)
      }
    }
  }

  /**
   * Actually save the crs in DB by batch of batchSize.
   * The final result is a failure if any batch were in failure.
   */
  private[this] def saveResults(
      crs           : Seq[T]
    , save          : Seq[T] => Box[Seq[T]]
    , successLogger : Seq[MigrableEntity] => Unit
    , batchSize     : Int
  ) : Box[Int] = {
    (bestEffort(crs.grouped(batchSize).toSeq) { seq =>
      val res = save(seq) ?~! "Error when saving logs (ids: %s)".format(seq.map( _.id).sorted.mkString(","))
      res.foreach { seq => successLogger(seq) }
      res
    }).map( _.flatten ).map( _.size ) //flatten else we have Box[Seq[Seq]]]
  }


}


/**
 * A service able to migrate raw XML eventLog
 * of entity (rules, groups, directives)
 * up to the current file format.
 *
 * We only support these elements:
 * - directive related:
 *   - activeTechniqueCategory (policyLibraryCategory)
 *   - activeTechnique  (policyLibraryTemplate)
 *   - directive  (policyInstance)
 * - rules related:
 *   - rule (configurationRule)
 * - groups related:
 *   - nodeGroupCategory
 *   - nodeGroup
 */
trait XmlEntityMigration {

  def getUpToDateXml(entity:Elem) : Box[Elem]

}


/**
 * Implementation
 */
class DefaultXmlEventLogMigration(
    xmlMigration_2_3: XmlMigration_2_3
) extends XmlEntityMigration {

  def getUpToDateXml(entity:Elem) : Box[Elem] = {

    for {
      versionT <- Box(entity.attribute("fileFormat").map( _.text )) ?~! "Can not migrate element with unknow fileFormat: %s".format(entity)
      version  <- try { Full(versionT.toFloat.toInt) } catch { case e:Exception => Failure("Bad version (expecting an integer or a float: '%s'".format(versionT))}
      migrate  <- version match {
                    case 2 => migrate2_3(entity)
                    case 3 => Full(entity)
                    case x => Failure("Can not migrate XML file with fileFormat='%s' (expecting 1,2 or 3)".format(version))
                  }
    } yield {
      migrate
    }
  }

  private[this] def migrate2_3(xml:Elem) : Box[Elem] = {
    xml.label match {
      case "rule" => xmlMigration_2_3.rule(xml)
      case _ => xmlMigration_2_3.other(xml)
    }
  }

}


