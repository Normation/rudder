package com.normation.rudder.migration


import scala.xml.Elem
import scala.xml.Node
import scala.xml.NodeSeq

import com.normation.rudder.db.DB
import com.normation.rudder.domain.logger.MigrationLogger
import com.normation.utils.Control. _


import org.joda.time.DateTime

import net.liftweb.common._

import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._
import doobie._, doobie.implicits._
import cats.implicits._



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
    //scala XML is lying, the contract is to call trim and
    //returned an Elem, not a Node.
    //See scala.xml.Utility#trim implementation.
    val trimed = scala.xml.Utility.trim(xml).asInstanceOf[Elem]
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


sealed trait MigrationStatus
final case object NoMigrationRequested extends MigrationStatus
final case object MigrationVersionNotHandledHere extends MigrationStatus
final case object MigrationVersionNotSupported extends MigrationStatus
final case class  MigrationSuccess(migrated:Int) extends MigrationStatus


trait MigrableEntity {
  def id: Long
  def data: Elem
}

case class MigrationEventLog(
    id       : Long
  , eventType: String
  , data     : Elem
) extends MigrableEntity


case class MigrationChangeRequest(
    id  : Long
  , name: String
  , data: Elem
) extends MigrableEntity


/**
 * This class manages the high level migration process: read if a
 * migration is required in the MigrationEventLog datatable, launch
 * the migration process, write migration result.
 * The actual migration of event logs is delegated to a lit of
 * batchMigrators.
 * If too old fileFormat are found, their migration is delegated to older
 * ControlXmlFileFormatMigration
 */
trait ControlXmlFileFormatMigration extends XmlFileFormatMigration {

  def migrationEventLogRepository: MigrationEventLogRepository
  def batchMigrators             : List[BatchElementMigration[_]]
  def previousMigrationController: Option[ControlXmlFileFormatMigration]

  def migrate() : Box[MigrationStatus] = {

    /*
     * test is we have to migrate, and execute migration
     */
    migrationEventLogRepository.getLastDetectionLine match {
        case Left(ex) => Failure("Error when retrieving migration information", Full(ex), Empty)
        case Right(None) =>
          logger.info(s"No migration detected by migration script (table '${migrationEventLogRepository.table}' is empty or does not exist)")
          Full(NoMigrationRequested)

        /*
         * we only have to deal with the migration if:
         * - fileFormat is == fromVersion AND (
         *   - migrationEndTime is not set OR
         *   - migrationFileFormat == fromVersion
         * )
         */

        //new migration
        case Right(Some(DB.MigrationEventLog(
            id
          , _
          , detectedFileFormat
          , migrationStartTime
          , migrationEndTime : None.type
          , _
          , _
        ))) if(detectedFileFormat == fromVersion) =>
          /*
           * here, simply start a migration for the first time (if migrationStartTime is None)
           * or continue a previously started migration (but interrupted ?)
           */
          if(migrationStartTime.isEmpty) {
            migrationEventLogRepository.setMigrationStartTime(id, DateTime.now)
          }

          val migrationResults = batchMigrators.map { migrator =>
            logger.info(s"Start migration of ${migrator.elementName} from format '${fromVersion}' to '${toVersion}'")

            migrator.process() match {
              case Full(MigrationProcessResult(i, nbBatches)) =>
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
              migrationEventLogRepository.setMigrationFileFormat(id, toVersion.toLong, DateTime.now)
              logger.info(s"Completed migration to file format '${toVersion}', ${numberMigrated} records migrated")
              Full(MigrationSuccess(numberMigrated))
            case eb:EmptyBox => eb
          }

        //a past migration was done, but the final format is not the one we want
        case Right(Some(x@DB.MigrationEventLog(
            _
          , _
          , _
          , _
          , Some(endTime)
          , Some(migrationFileFormat)
          , _
        ))) if(migrationFileFormat == fromVersion) =>
          //create a new status line with detected format = migrationFileFormat,
          //and a description to say why we recurse
          migrationEventLogRepository.createNewStatusLine(migrationFileFormat, Some(s"Found a post-migration fileFormat='${migrationFileFormat}': update"))
          this.migrate()

            // lower file format found, send to parent)
        case Right(Some(status@DB.MigrationEventLog(
            _
          , _
          , detectedFileFormat
          , migrationStartTime
          , migrationEndTime : None.type
          , _
          , _
        ))) if(detectedFileFormat < fromVersion) =>


          logger.info("Found and older migration to do")
          previousMigrationController match {
            case None =>
              logger.info(s"The detected format ${detectedFileFormat} is not supported anymore, you will have to " +
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
        case Right(Some(x@DB.MigrationEventLog(
            _
          , _
          , _
          , _
          , Some(endTime)
          , Some(migrationFileFormat)
          , _
        ))) if(migrationFileFormat < fromVersion) =>
          //create a new status line with detected format = migrationFileFormat,
          //and a description to say why we recurse
          previousMigrationController.foreach { migrator =>
            migrator.migrate()
          }
          this.migrate()


        //other case: does nothing
        case Right(Some(x)) =>
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

final case class MigrationProcessResult(
    totalMigrated: Int
  , nbBatches    : Int
)

/**
 * A trait that explain how to migrate one type of data in data base
 * (it delelegates the actual XML migration to someone else)
 */
trait BatchElementMigration[T <: MigrableEntity] extends XmlFileFormatMigration {

  def individualMigration: IndividualElementMigration[T]
  def selectAllSqlRequest(batchSize: Int): ConnectionIO[Vector[T]]
  def batchSize : Int
  def doobie: Doobie

  //human readable name of elements to migrate, for logs
  def elementName: String

  /**
   * Retrieve eventlog for the migration, limited to max batchSize
   */
  def findBatch: ConnectionIO[Vector[T]] = {
    for {
      results <- selectAllSqlRequest(batchSize)
    } yield {
      results.filter( log => try {
          (log.data \\ "@fileFormat" exists { _.text.toInt == fromVersion })
        } catch {
          case e:NumberFormatException => false
        }
      )
    }
  }

  /**
   * Save a list of change
   */
  protected def save(logs: Vector[T]) : ConnectionIO[Vector[T]]

  /**
   * General algorithm: get all change request to migrate,
   * then process and save them.
   * Return the number of change request migrated.
   * The get/save is done in batch of batchSize elements
   */
  def process() : Box[MigrationProcessResult] = {
    def recProcessOneBatch(mig: MigrationProcessResult): Box[MigrationProcessResult] = {
      val exec: ConnectionIO[Vector[T]] = (for {
        elts     <- findBatch
        migrated =  migrate(elts, errorLogger)
        saved    <- save(migrated)
      } yield {
        saved
      })

      val res = exec.transact(doobie.xa).attempt.unsafeRunSync

      res match {
        case Right(k) if(k.size < 1) =>
          logger.debug(s"Migration from file format ${fromVersion} to ${toVersion} ended after ${mig.nbBatches} batches")
          Full(mig)
        case Right(k) =>
          successLogger(k)
          logger.debug(s"Migration from file format ${fromVersion} to ${toVersion}: starting batch #${mig.nbBatches+1}")
          recProcessOneBatch(MigrationProcessResult(mig.totalMigrated+k.size, mig.nbBatches+1))
        case Left(ex) =>
          val f = Failure("Error when migrating eventlog", Full(ex), Empty)
          if(mig.nbBatches > 0) f ?~! s"(already migrated ${mig.nbBatches} entries"
          else f
      }
    }
    logger.info(s"Starting batch migration from file format ${fromVersion} to ${toVersion} by batch of ${batchSize} events")
    recProcessOneBatch(MigrationProcessResult(0,0))
  }

  private[this] def migrate(
      elements     : Vector[T]
    , errorLogger  : Failure => Unit
  ) : Vector[T] = {

    elements.flatMap { elt =>
      individualMigration.migrate(elt) match {
        case eb:EmptyBox =>
          errorLogger(eb ?~! s"Error when trying to migrate change request with id '${elt.id}'")
          None
        case Full(m)     =>
          if(m == elt) { //something goes wrong, not modificatiion ?
            errorLogger(Failure(s"Error when trying to migrate change request with id '${elt.id}' (no modification)"))
            None
          } else {
            Some(m)
          }
      }
    }
  }


}


/**
 * The migration for eventlogs.
 * This is the intersting part for requests
 */

trait EventLogsMigration extends BatchElementMigration[MigrationEventLog] {

  override final val elementName = "EventLog"

  override final def selectAllSqlRequest(batchSize: Int): ConnectionIO[Vector[MigrationEventLog]] = {
    sql"""
      select id, eventType, data from (select id, eventType, data, ((xpath('/entry//@fileFormat',data))[1]::text) as version from eventlog) as T
      where version=${fromVersion.toString} limit ${batchSize}
    """.query[MigrationEventLog].to[Vector]
  }

  final protected def save(logs: Vector[MigrationEventLog]) : ConnectionIO[Vector[MigrationEventLog]] = {
    val sql = "update eventlog set eventtype = ?, data = ? where id = ?"
    for {
      _ <- Update[(String, Elem, Long)](sql).updateMany(logs.map(l => (l.eventType, l.data, l.id)))
    } yield {
      logs
    }
  }
}

trait ChangeRequestsMigration extends BatchElementMigration[MigrationChangeRequest] {

  override final val elementName = "ChangeRequest"

  override final def selectAllSqlRequest(batchSize:Int): ConnectionIO[Vector[MigrationChangeRequest]] = {
    sql"""select id, name, content from (select id, name, content, ((xpath('/changeRequest/@fileFormat', content))[1]::text) as version from changerequest) as T
        where version=${fromVersion.toString} limit ${batchSize}""".query[MigrationChangeRequest].to[Vector]
  }


  override protected def save(logs: Vector[MigrationChangeRequest]) : ConnectionIO[Vector[MigrationChangeRequest]] = {
    val sql = "update changerequest set content = ? where id = ?"
    for {
      _ <- Update[(Elem, Long)](sql).updateMany(logs.map(l => (l.data, l.id)))
    } yield {
      logs
    }
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
object DefaultXmlEventLogMigration extends XmlEntityMigration {

  def getUpToDateXml(entity:Elem) : Box[Elem] = {

    for {
      versionT <- Box(entity.attribute("fileFormat").map( _.text )) ?~! s"Can not migrate element with unknow fileFormat: ${entity}"
      version  <- try { Full(versionT.toFloat.toInt) } catch { case e:Exception => Failure(s"Bad version (expecting an integer or a float: '${versionT}'")}
      migrate  <- version match {
                    case 5 => migrate5_6(entity)
                    case 6 => Full(entity)
                    case x => Failure(s"Can not migrate XML file with fileFormat='${version}' (expecting 2,3,4 or 5)")
                  }
    } yield {
      migrate
    }
  }

  private[this] def migrate5_6(xml:Elem) : Box[Elem] = {
      XmlMigration_5_6.other(xml)
  }
}


