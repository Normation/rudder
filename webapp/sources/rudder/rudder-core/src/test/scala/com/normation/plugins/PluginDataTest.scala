package com.normation.plugins

import java.time.ZonedDateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PluginDataTest extends Specification {
  import GlobalPluginsLicense.*
  import PluginInstallStatus.*

  "PluginSystemStastus" should {
    "map from a disabled webapp plugin" in {
      PluginInstallStatus.from(PluginType.Webapp, installed = true, enabled = false) === Disabled
    }
    "not map from an integration plugin to 'disabled'" in {
      PluginInstallStatus.from(PluginType.Integration, installed = true, enabled = false) === Enabled
    }
  }

  "GlobalPluginLicense" should {
    "aggregate plugins license" in {
      val startDate = ZonedDateTime.parse("2025-02-05T18:40:00+01:00")
      val endDate   = startDate.plusMonths(2)
      val license   = {
        PluginLicense(
          Licensee("test"),
          SoftwareId(""),
          MinVersion(""),
          MaxVersion(""),
          startDate,
          endDate,
          MaxNodes(None),
          Map.empty
        )
      }

      "with maximum start date and minimum end date" in {
        import GlobalPluginsLicense.EndDateImplicits.minZonedDateTime
        val maxStart = startDate.plusDays(1)
        val minEnd   = endDate.minusDays(1)
        val a        = GlobalPluginsLicense.fromLicense[ZonedDateTime](license)
        val b        = GlobalPluginsLicense.fromLicense[ZonedDateTime](license.copy(startDate = maxStart, endDate = minEnd))

        val combined = a.combine(b)
        combined.startDate must beSome(maxStart)
        combined.endDate must beSome(minEnd)
      }

      "with minimum number of allowed nodes" in {
        val unlimited = GlobalPluginsLicense.fromLicense[DateCounts](license)
        val ten       = GlobalPluginsLicense.fromLicense[DateCounts](license.copy(maxNodes = MaxNodes(Some(10))))
        val hundred   = GlobalPluginsLicense.fromLicense[DateCounts](license.copy(maxNodes = MaxNodes(Some(100))))

        unlimited.combine(ten).maxNodes must beSome(===(10))
        ten.combine(hundred).maxNodes must beSome(===(10))
      }

      "with end date counts" in {
        val uniqueLicense = GlobalPluginsLicense.fromLicense[DateCounts](license)

        uniqueLicense.combine(uniqueLicense).endDate must beSome(beLike[DateCounts] {
          case d => d.value must containTheSameElementsAs((endDate -> DateCount(endDate, 2)) :: Nil)
        })
      }
    }
  }
}
