package com.normation.rudder.domain.eventlog

import org.junit.runner.RunWith
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class EventLogTypeTranslateTest extends ZIOSpecDefault {
  override def spec: Spec[Any, Any] = {
    suite("EventLogTypeTranslate")(
      suite("old event types (nodes format pre-v6) have their own translation")(
        EventTypeFactory.extraEventTypeMap.keys
          .map(e => {
            test(e) {
              val res = EventLogTypeTranslate(e)
              assertTrue(res != ModifyNodeEventType.serialize && res != e)
            }
          })
      ),
      suite("every event type serialization has a translation")(
        EventTypeFactory.eventTypes
          .map(e => {
            test(e.getClass.getSimpleName) {
              val res = EventLogTypeTranslate(e.serialize)
              assertTrue(res != e.serialize)
            }
          })
      ),
      test("event without match is not translated")(
        check(Gen.string.filterNot(EventTypeFactory.eventTypesMap.contains)) { s =>
          val res = EventLogTypeTranslate(s)
          assertTrue(res == s)
        }
      )
    )
  }

}
