package com.normation.rudder.services.eventlog

import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLogRequest
import com.normation.rudder.EventLogRepositoryMock
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.TenantAccessGrant
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class EventLogServiceImplTest extends Specification {

  sequential

  "event log service" should {

    "apply some filter in the event log coming from the repository" in {

      val filter = Some(
        EventLogRequest(
          0,
          10,
          id = Some(EventLogRequest.Id(123)),
          objectId = None,
          search = None,
          startDate = None,
          endDate = None,
          principal = None,
          order = None,
          typeFilter = None
        )
      )
      eventLogServiceImpl.getUserEventLogs(filter).map(_.size).orElseSucceed(0) must beEqualTo(1)
    }
  }

  class EventLogRepositoryMockWithResults extends EventLogRepositoryMock {}

  implicit val qc: QueryContext = QueryContext(actor = EventActor("rudder"), accessGrant = TenantAccessGrant.All)
  val filter = None // FIXME

  val eventLogRepository: EventLogRepository = new EventLogRepositoryMock()

  val eventLogServiceImpl =
    new EventLogServiceImpl(eventLogRepository)

}
