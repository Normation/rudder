/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.datasources

import net.liftweb.common.Box
import ch.qos.logback.core.db.DataSourceConnectionSource
import com.normation.eventlog.EventActor
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import org.joda.time.DateTime
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import com.normation.rudder.domain.parameters.Parameter
import com.normation.utils.StringUuidGenerator
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog._
import scala.concurrent.duration._
import net.liftweb.common.Failure

final case class PartialNodeUpdate(
    nodes        : Map[NodeId, NodeInfo] //the node to update
  , policyServers: Map[NodeId, NodeInfo] //there policy servers
  , parameters   : Set[Parameter]
)

trait DataSourceRepository {

  def getAllWithStatus : Box[Map[DataSourceId,(DataSource,DataSourceStatus)]]
  def getAll : Box[Map[DataSourceId,DataSource]]

  def get(id : DataSourceId) : Box[Option[DataSource]]

  def getStatus(id : DataSourceId) : Box[Option[DataSourceStatus]]

  def save(source : DataSource) : Box[DataSource]
  def saveStatus(source : DataSourceId, status : DataSourceStatus) : Box[DataSourceStatus]

  def delete(id : DataSourceId) : Box[DataSource]
}

/*
 * A trait that exposes interactive callbacks for
 * data sources, i.e the method to call when one
 * need to update datasources.
 */
trait DataSourceUpdateCallbacks {

  def onNewNode(node: NodeId): Unit
  def onGenerationStarted(generationTimeStamp: DateTime): Unit
  def onUserAskUpdateAll(actor: EventActor): Unit
  def onUserAskUpdateNode(actor: EventActor, nodeId: NodeId): Unit

  /*
   * Initialise all datasource so that they are ready to schedule their
   * first data fetch or wait for other callbacks.
   *
   * Non periodic data source won't be updated with that call.
   * Periodic one will be updated in a random interval between
   * 1 minute and min(period / 2, 30 minute) to avoid to extenghish
   * all resources on them.
   */
  def startAll(): Unit
}

class MemoryDataSourceRepository extends DataSourceRepository {

  private[this] var sources : Map[DataSourceId,DataSource] = Map()

  private[this] var statuses : Map[DataSourceId,DataSourceStatus] = Map()

  def getAllWithStatus() = synchronized{
      Full(for {
        (sourceId,source) <- sources
        status = statuses.getOrElse(sourceId, DataSourceStatus(None,Map()))
      } yield {
        (sourceId,(source,status))
      })
  }

  def getAll() = synchronized{
    Full(sources)
  }

  def get(id : DataSourceId) : Box[Option[DataSource]]= synchronized(Full(sources.get(id)))
  def getStatus(id : DataSourceId) : Box[Option[DataSourceStatus]]= synchronized(Full(statuses.get(id)))

  def save(source : DataSource) = synchronized {
    sources = sources +  ((source.id,source))
    Full(source)
  }

  def saveStatus(sourceId : DataSourceId, status : DataSourceStatus) : Box[DataSourceStatus] = synchronized {
    statuses = statuses +  ((sourceId,status))
    Full(status)
  }

  def delete(id : DataSourceId) : Box[DataSource] = synchronized {
    sources.get(id) match {
      case Some(source) =>
        sources = sources - (id)
        statuses = statuses - id
        Full(source)
      case None =>
        Failure(s"Data source '${id}' does not exists, and thus can't be deleted")
    }
  }
}
/**
 * This is the higher level repository facade that is managine the "live"
 * instance of datasources, with the scheduling initialisation and update
 * on different repository action.
 *
 * It doesn't deal at all with the serialisation / deserialisation of data source
 * in data base.
 */
class DataSourceRepoImpl(
    backend: DataSourceRepository
  , fetch  : QueryDataSourceService
  , uuidGen: StringUuidGenerator
) extends DataSourceRepository with DataSourceUpdateCallbacks {

  private[this] var datasources = Map[DataSourceId, DataSourceScheduler]()

  // utility methods on datasources
  // stop a datasource - must be called when the datasource still in "datasources"
  private[this] def stop(id: DataSourceId) = datasources.get(id).foreach( _.cancel() )
  // get datasource scheduler which match the condition
  private[this] def foreachDatasourceScheduler(condition: DataSource => Boolean)(action: DataSourceScheduler => Unit): Unit = {
    datasources.filter { case(_, dss) => condition(dss.datasource) }.foreach { case (_, dss) => action(dss) }
  }
  private[this] def updateDataSourceScheduler(source: DataSource, delay: Option[FiniteDuration]): Unit = {
    //need to cancel if one exists
    stop(source.id)
    // create live instance
    import monix.execution.Scheduler.Implicits.global
    val dss = new DataSourceScheduler(
          source
        , global
        , () => ModificationId(uuidGen.newUuid)
        , (cause: UpdateCause) => fetch.queryAll(source, cause)
    )
    datasources = datasources + (source.id -> dss)
    //start new
    delay match {
      case None    => dss.start()
      case Some(d) => dss.startWithDelay(d)
    }
  }

  ///
  ///         DB READ ONLY
  /// read only method are just forwarder to backend
  ///
  override def getAll : Box[Map[DataSourceId,DataSource]] = {
    DataSourceLogger.info(s"Live data sources: ${datasources.map(_._2.datasource.name.value).mkString("; ")}")
    backend.getAll
  }
  override def getAllWithStatus = {
    DataSourceLogger.info(s"Live data sources: ${datasources.map(_._2.datasource.name.value).mkString("; ")}")
    backend.getAllWithStatus
  }
  override def get(id : DataSourceId) : Box[Option[DataSource]] = backend.get(id)
  override def getStatus(id : DataSourceId) : Box[Option[DataSourceStatus]] = backend.getStatus(id)

  ///
  ///         DB WRITE ONLY
  /// write methods need to manage the "live" scheduler
  /// write methods need to be synchronised to keep consistancy in
  /// "live" scheduler and avoid a missed add in a doube save for ex.
  ///

  /*
   * on update, we need to stop the corresponding optionnaly existing
   * scheduler, and update with the new one.
   */
  override def save(source : DataSource) : Box[DataSource] = synchronized {
    //only create/update the "live" instance if the backend succeed
    backend.save(source) match {
      case eb: EmptyBox =>
        val msg = (eb ?~! s"Error when saving data source '${source.name.value}' (${source.id.value})").messageChain
        DataSourceLogger.error(msg)
        eb
      case Full(s)      =>
        updateDataSourceScheduler(source, delay = None)
        DataSourceLogger.debug(s"Data source '${source.name.value}' (${source.id.value}) udpated")
        Full(s)
    }
  }
  override def saveStatus(sourceId : DataSourceId, status:DataSourceStatus) : Box[DataSourceStatus] = synchronized {
    backend.saveStatus(sourceId, status)
  }

  /*
   * delete need to clean existing live resource
   */
  override def delete(id : DataSourceId) : Box[DataSource] = synchronized {
    //start by cleaning
    stop(id)
    datasources = datasources - (id)
    backend.delete(id)
  }

  ///
  ///        Status update
  ///

  // It should get the status from the database and update it (do not replace status of all nodes)
  private[this] def updateOneNodeStatus (dataSource : DataSource, nodeId : NodeId, updateDate: DateTime, updateResult : Box[(NodeId,DataSourceUpdateStatus)] ) = {
    for {
      baseStatus <- getStatus(dataSource.id) match {
          case Full(Some(status)) =>
            Full(status)
          case Full(None) =>
            Full(DataSourceStatus(None,Map()))
          case eb:EmptyBox =>
            eb ?~! s"could not update status of datasource ${dataSource.id.value} after update of datasource data for Node ${nodeId.value}"
        }
      newStatus = updateResult match {
        case Full((_,updateResult)) => updateResult
        case eb:EmptyBox =>
          val message = eb ?~! s" An error occured during update of datasource data for Node ${nodeId.value}"
          val lastSuccess : Option[DateTime] = baseStatus.nodesStatus.get(nodeId).flatMap {
            case DataSourceUpdateSuccess(date) => Some(date)
            case DataSourceUpdateFailure(_,_,optDate) => optDate
          }
          DataSourceUpdateFailure(updateDate, message.messageChain,lastSuccess)
      }
      updatedStatus = baseStatus.copy(Some(newStatus), baseStatus.nodesStatus + ((nodeId,newStatus)))

    } yield {
      saveStatus(dataSource.id, updatedStatus)
    }
  }

  private[this] def updateAllNodeStatus (dataSource : DataSource, updateDate: DateTime, updateResult : Box[Map[NodeId,DataSourceUpdateStatus]] ) = {
    for {
      baseStatus <- getStatus(dataSource.id) match {
          case Full(Some(status)) =>
            Full(status)
          case Full(None) =>
            Full(DataSourceStatus(None,Map()))
          case eb:EmptyBox =>
            eb ?~! s"could not update status of datasource ${dataSource.id.value} after update of datasource data for all Nodes"
        }
      newStatuses = updateResult match {
        case Full(results) =>
           DataSourceStatus(Some(DataSourceUpdateSuccess(updateDate)),results)
        case eb:EmptyBox =>
          val message = eb ?~! s" An error occured during update of datasource data for all Nodes}"
          val lastSuccess : Option[DateTime] = baseStatus.lastRunDate.flatMap {
            case DataSourceUpdateSuccess(date) => Some(date)
            case DataSourceUpdateFailure(_,_,optDate) => optDate
          }
          DataSourceStatus(Some(DataSourceUpdateFailure(updateDate, message.messageChain,lastSuccess)), baseStatus.nodesStatus)
      }
      //updatedStatus = baseStatus.copy(Some(updateDate), baseStatus.nodesStatus + ((nodeId,newStatus)))

    } yield {
      saveStatus(dataSource.id, newStatuses)
    }
  }

  ///
  ///        CALLBACKS
  ///

  // no need to synchronize callback, they only
  // need a reference to the immutable datasources map.

  override def onNewNode(nodeId: NodeId): Unit = {
    DataSourceLogger.info(s"Fetching data from data source for new node '${nodeId}'")
    foreachDatasourceScheduler(ds => ds.enabled && ds.runParam.onNewNode){ dss =>
      val msg = s"Fetching data for data source ${dss.datasource.name.value} (${dss.datasource.id.value}) for new node '${nodeId.value}'"
      DataSourceLogger.debug(msg)
      //no scheduler reset for new node
      val updateDate = DateTime.now()

      val updated = fetch.queryOne(dss.datasource, nodeId, UpdateCause(
          ModificationId(uuidGen.newUuid)
        , RudderEventActor
        , Some(msg)
      ))

      updateOneNodeStatus(dss.datasource, nodeId, updateDate, updated)
    }

  }

  override def onGenerationStarted(generationTimeStamp: DateTime): Unit = {
    DataSourceLogger.info(s"Fetching data from data source for all node for generation ${generationTimeStamp.toString()}")
    foreachDatasourceScheduler(ds => ds.enabled && ds.runParam.onGeneration){ dss =>
      //for that one, do a scheduler restart
      val msg = s"Getting data for source ${dss.datasource.name.value} for policy generation started at ${generationTimeStamp.toString()}"
      DataSourceLogger.debug(msg)
      dss.doActionAndSchedule{
        val updateDate = DateTime.now()
        val updatedNodes = fetch.queryAll(dss.datasource, UpdateCause(ModificationId(uuidGen.newUuid), RudderEventActor, Some(msg)))
        updateAllNodeStatus(dss.datasource, updateDate, updatedNodes)
      }
    }
  }

  override def onUserAskUpdateAll(actor: EventActor): Unit = {
    DataSourceLogger.info(s"Fetching data from data source for all node because ${actor.name} asked for it")
    foreachDatasourceScheduler(ds => ds.enabled){ dss =>
      //for that one, do a scheduler restart
      val msg = s"Refreshing data from data source ${dss.datasource.name.value} on user ${actor.name} request"
      DataSourceLogger.debug(msg)
      dss.doActionAndSchedule {
        val updateDate = DateTime.now()
        val updatedNodes = fetch.queryAll(dss.datasource, UpdateCause(ModificationId(uuidGen.newUuid), actor, Some(msg)))
        updateAllNodeStatus(dss.datasource, updateDate, updatedNodes)
      }
    }
  }

  override def onUserAskUpdateNode(actor: EventActor, nodeId: NodeId): Unit = {
    DataSourceLogger.info(s"Fetching data from data source for node '${nodeId.value}' because '${actor.name}' asked for it")
    foreachDatasourceScheduler(ds => ds.enabled){ dss =>
      //for that one, no scheduler restart
      val msg = s"Fetching data for data source ${dss.datasource.name.value} (${dss.datasource.id.value}) for node '${nodeId.value}' on user '${actor.name}' request"
      DataSourceLogger.debug(msg)
      val updateDate = DateTime.now()
      val updated = fetch.queryOne(dss.datasource, nodeId, UpdateCause(ModificationId(uuidGen.newUuid), RudderEventActor, Some(msg)))

      updateOneNodeStatus(dss.datasource, nodeId, updateDate, updated)
    }
  }

  override def startAll() = {
    //sort by period (the least frequent the last),
    //then start them every minutes
    val toStart = datasources.values.flatMap { dss =>
      dss.datasource.runParam.schedule match {
        case Scheduled(d) => Some((d, dss))
        case _            => None
      }
    }.toList.sortBy( _._1.toMillis ).zipWithIndex

    toStart.foreach { case ((period, dss), i) =>
      dss.startWithDelay((i+1).minutes)
    }
  }

}
