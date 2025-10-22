/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

package com.normation.plugins

import better.files.*
import com.normation.utils.ParseVersion
import com.normation.utils.Version
import com.normation.zio.*
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RudderPluginJsonTest extends Specification {
  implicit class ForceParse(s: String) {
    def toVersion: Version = ParseVersion.parse(s) match {
      case Left(err) => throw new IllegalArgumentException(s"Can not parse '${s}' as a version in test: ${err}")
      case Right(v)  => v
    }
  }

  val index_json: String = """{
                             |  "plugins": {
                             |    "rudder-plugin-branding": {
                             |      "files": [
                             |        "/opt/rudder/share/plugins/",
                             |        "/opt/rudder/share/plugins/branding/",
                             |        "/opt/rudder/share/plugins/branding/branding.jar"
                             |      ],
                             |      "name": "rudder-plugin-branding",
                             |      "jar-files": [
                             |        "/opt/rudder/share/plugins/branding/branding.jar"
                             |      ],
                             |      "content": {
                             |        "files.txz": "/opt/rudder/share/plugins"
                             |      },
                             |      "version": "5.0-1.3",
                             |      "build-commit": "81edd3edf4f28c13821af8014da0520b72b9df94",
                             |      "build-date": "2018-10-11T10:23:40Z",
                             |      "type": "plugin"
                             |    },
                             |    "rudder-plugin-centreon": {
                             |      "files": [
                             |        "/opt/rudder//",
                             |        "/opt/rudder//bin/",
                             |        "/opt/rudder//bin/centreon-plugin",
                             |        "/opt/rudder//share/",
                             |        "/opt/rudder//share/python/",
                             |        "/opt/rudder//share/python/centreonapi/",
                             |        "/opt/rudder//share/python/centreonapi/__init__.py",
                             |        "/opt/rudder//share/python/centreonapi/webservice/",
                             |        "/opt/rudder//share/python/centreonapi/webservice/__init__.py",
                             |        "/opt/rudder//share/python/centreonapi/webservice/configuration/",
                             |        "/opt/rudder//share/python/centreonapi/webservice/configuration/__init__.py",
                             |        "/opt/rudder//share/python/centreonapi/webservice/configuration/hostgroups.py",
                             |        "/opt/rudder//share/python/centreonapi/webservice/configuration/service.py",
                             |        "/opt/rudder//share/python/centreonapi/webservice/configuration/templates.py",
                             |        "/opt/rudder//share/python/centreonapi/webservice/configuration/poller.py",
                             |        "/opt/rudder//share/python/centreonapi/webservice/configuration/host.py",
                             |        "/opt/rudder//share/python/centreonapi/centreon.py",
                             |        "/opt/rudder//share/python/centreonapi/__init__.pyc",
                             |        "/opt/rudder//share/python/ipaddress.py",
                             |        "/opt/rudder//etc/",
                             |        "/opt/rudder//etc/hooks.d/",
                             |        "/opt/rudder//etc/hooks.d/node-pre-deletion/",
                             |        "/opt/rudder//etc/hooks.d/node-pre-deletion/centreon-pre-deletion.sh",
                             |        "/opt/rudder//etc/hooks.d/node-post-acceptance/",
                             |        "/opt/rudder//etc/hooks.d/node-post-acceptance/centreon-post-acceptance.sh"
                             |      ],
                             |      "name": "rudder-plugin-centreon",
                             |      "content": {
                             |        "files.txz": "/opt/rudder/"
                             |      },
                             |      "version": "5.0-1.1",
                             |      "build-commit": "5c4592d93912ef56de0c506295d22fb2a86146ac",
                             |      "build-date": "2018-10-29T17:34:16Z",
                             |      "type": "plugin"
                             |    }
                             |  }
                             |}""".stripMargin

  val expected: List[JsonPluginDef] = List(
    JsonPluginDef(
      "rudder-plugin-branding",
      RudderPluginVersion("5.0.0".toVersion, "1.3.0".toVersion),
      List(
        "/opt/rudder/share/plugins/",
        "/opt/rudder/share/plugins/branding/",
        "/opt/rudder/share/plugins/branding/branding.jar"
      ),
      List("/opt/rudder/share/plugins/branding/branding.jar"),
      "81edd3edf4f28c13821af8014da0520b72b9df94",
      DateTime.parse("2018-10-11T10:23:40Z", ISODateTimeFormat.dateTimeNoMillis()).withZone(DateTimeZone.UTC)
    ),
    JsonPluginDef(
      "rudder-plugin-centreon",
      RudderPluginVersion("5.0.0".toVersion, "1.1.0".toVersion),
      List(
        "/opt/rudder//",
        "/opt/rudder//bin/",
        "/opt/rudder//bin/centreon-plugin",
        "/opt/rudder//share/",
        "/opt/rudder//share/python/",
        "/opt/rudder//share/python/centreonapi/",
        "/opt/rudder//share/python/centreonapi/__init__.py",
        "/opt/rudder//share/python/centreonapi/webservice/",
        "/opt/rudder//share/python/centreonapi/webservice/__init__.py",
        "/opt/rudder//share/python/centreonapi/webservice/configuration/",
        "/opt/rudder//share/python/centreonapi/webservice/configuration/__init__.py",
        "/opt/rudder//share/python/centreonapi/webservice/configuration/hostgroups.py",
        "/opt/rudder//share/python/centreonapi/webservice/configuration/service.py",
        "/opt/rudder//share/python/centreonapi/webservice/configuration/templates.py",
        "/opt/rudder//share/python/centreonapi/webservice/configuration/poller.py",
        "/opt/rudder//share/python/centreonapi/webservice/configuration/host.py",
        "/opt/rudder//share/python/centreonapi/centreon.py",
        "/opt/rudder//share/python/centreonapi/__init__.pyc",
        "/opt/rudder//share/python/ipaddress.py",
        "/opt/rudder//etc/",
        "/opt/rudder//etc/hooks.d/",
        "/opt/rudder//etc/hooks.d/node-pre-deletion/",
        "/opt/rudder//etc/hooks.d/node-pre-deletion/centreon-pre-deletion.sh",
        "/opt/rudder//etc/hooks.d/node-post-acceptance/",
        "/opt/rudder//etc/hooks.d/node-post-acceptance/centreon-post-acceptance.sh"
      ),
      List(),
      "5c4592d93912ef56de0c506295d22fb2a86146ac",
      DateTime.parse("2018-10-29T18:34:16+01:00", ISODateTimeFormat.dateTimeNoMillis()).withZone(DateTimeZone.UTC)
    )
  )

  "Plugins JSON service" should {
    "be able to read json file format" in {
      ReadPluginPackageInfo.parseJsonPluginFileDefs(index_json) must beRight(containTheSameElementsAs(expected))
    }

    val withIndexFile = for {
      tmp <- File.temporaryFile("index.json")
      os  <- tmp.outputStream
      is  <- new Dispose(Resource.getAsStream("plugins-index.json"))
    } yield {
      IOUtils.copy(is, os)
      tmp
    }
    "be able to read all plugins" in withIndexFile { f =>
      val read = new ReadPluginPackageInfo(f)
      read.getInfo().either.runNow must beRight(haveSize[List[?]](11))
    }
  }
}
