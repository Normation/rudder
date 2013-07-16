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
import com.normation.utils.XmlUtils
import scala.xml.Node

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

object TestLabel {
  def apply(xml:Node, label:String) : Box[Node] = {
    if(xml.label == label) Full(xml)
    else Failure("Entry type is not a '%s' : %s".format(label, xml) )
  }
}


object TestIsElem {

  private[this] def failBadElemType(xml:NodeSeq) = {
    Failure("Not expected type of NodeSeq (wish it was an Elem): " + xml)
  }

  def apply(xml:NodeSeq) : Box[Elem] = {
    xml match {
      case seq if(seq.size == 1) => seq.head match {
        case e:Elem => Full(e)
        case x => failBadElemType(x)
      }
      case x => failBadElemType(x)
    }
  }
}


//test that the node is an entry and that it has EXACTLY one child
//do not use to test empty entry
//return the child
object TestIsEntry {
  def apply(xml:Elem) : Box[Elem] = {
    val trimed = XmlUtils.trim(xml)
    if(trimed.label.toLowerCase == "entry" && trimed.child.size == 1) TestIsElem(trimed.child.head)
    else Failure("Given XML data has not an 'entry' root element and exactly one child: " + trimed)
  }
}


/**
 * Change labels of a list of Elem
 */
case class ChangeLabel(label:String, logger: Logger) extends Function1[NodeSeq, Option[Elem]] {

  override def apply(nodes:NodeSeq) = nodes match {
    case e:Elem => Some(e.copy(label = label))
    case x => //ignore other type of nodes
      logger.debug("Can not change the label to '%s' of a NodeSeq other than elem in a CssSel: '%s'".format(label, x))
      None
  }
}

/**
 * Change labels of a list of Elem
 */
case class EncapsulateChild(label:String, logger:Logger) extends Function1[NodeSeq, Option[NodeSeq]] {

  override def apply(nodes:NodeSeq) = nodes match {
    case e:Elem => Some(e.copy(child = Encapsulate(label, logger).apply(e.child).getOrElse(NodeSeq.Empty)))
    case x => //ignore other type of nodes
      logger.debug("Can not change the label to '%s' of a NodeSeq other than elem in a CssSel: '%s'".format(label, x))
      None
  }
}
/**
 * Change labels of a list of Elem
 */
case class Encapsulate(label:String, logger: Logger) extends Function1[NodeSeq, Option[NodeSeq]] {

  override def apply(nodes:NodeSeq) = nodes match {
    case e:Elem => Some(e.copy(label=label,child=e))
    case nodeseq:NodeSeq if (nodeseq.size == 1) => Some(<test>{nodeseq.head}</test>.copy(label = label) )
    case nodeseq:NodeSeq if (nodeseq == NodeSeq.Empty) => Some(nodeseq)
    case x => //ignore other type of nodes
      logger.debug("Can not change the label to '%s' of a NodeSeq other than elem in a CssSel: '%s'".format(label, x))
      None
  }
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
            logger.info(s"The detected format ${detectedFileFormat} is no more supported, you will have to " +
            		"use an installation of Rudder that understand it to do the migration. For information, " +
            		"Rudder 2.6.x is the last major version which is able to import file format 1.0")
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
  , xmlMigration_3_4: XmlMigration_3_4
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

  private[this] def migrate3_4(xml:Elem) : Box[Elem] = {
    xml.label match {
      case "changeRequest" => xmlMigration_3_4.changeRequest(xml)
      case _ => xmlMigration_3_4.other(xml)
    }
  }


  private[this] def migrate2_4(xml:Elem) : Box[Elem] = {
    for {
      a <- migrate2_3(xml)
      b <- migrate3_4(a)
    } yield {
      b
    }
  }
}


