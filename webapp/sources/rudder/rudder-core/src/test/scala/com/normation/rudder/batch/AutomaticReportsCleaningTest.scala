package com.normation.rudder.batch

import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.syntax.UnboundidEntry
import com.normation.rudder.batch.AutomaticReportsCleaning.RunInterval
import org.junit.runner.RunWith
import scala.util.chaining.*
import zio.Scope
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class AutomaticReportsCleaningTest extends ZIOSpecDefault {
  import AutomaticReportsCleaningTest.*

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("AutomaticReportsCleaning")(
      test("parse from empty LDAPEntry")(
        assertTrue(
          AutomaticReportsCleaning.getRunInterval(emptyEntry) == RunInterval.default
        )
      ),
      test("parse non-empty LDAPEntry")(
        check(genEntry)((e, i) => {
          assertTrue(
            AutomaticReportsCleaning.getRunInterval(e) == RunInterval(i)
          )
        })
      )
    )
  }

}

private object AutomaticReportsCleaningTest {
  def emptyEntry: LDAPEntry = new LDAPEntry(new UnboundidEntry("ou=OU"))

  val genEntry: Gen[Any, (LDAPEntry, Int)] = {
    Gen.int.map(i => {
      emptyEntry.tap(entry => entry.addValues(AutomaticReportsCleaning.runIntervalAttr, s"""{"interval": ${i}}""")) -> i
    })
  }
}
