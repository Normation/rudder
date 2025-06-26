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

package com.normation.rudder.services.policies

import better.files.File
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import com.normation.inventory.domain.Certificate
import com.normation.inventory.domain.NodeId
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.zio.ZioRuntime
import com.softwaremill.quicklens.*
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import zio.*

@RunWith(classOf[JUnitRunner])
class TestWriteNodeCertificatesPem extends Specification {

  val cert1: String = """-----BEGIN CERTIFICATE-----
                        |MIIFgTCCA2mgAwIBAgIUXpY2lv7l+hkx4mVP324d9O1qJh0wDQYJKoZIhvcNAQEL
                        |BQAwUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlROMTQwMgYKCZImiZPyLGQBAQwk
                        |YjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1ZTU4MzEzMB4XDTE5MDcxMjE2
                        |MTYxMloXDTI3MDkyODE2MTYxMlowUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlRO
                        |MTQwMgYKCZImiZPyLGQBAQwkYjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1
                        |ZTU4MzEzMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEApW5up//FLSHr
                        |J14iIX7aSTGiVvJ5XTXHXxmx3O1MyFIrNoWoonmR7Wkii+FIcxk8LVajjMaBVP32
                        |ZbfEr1BqljV/XULTO4ivQoqJCfoq/2O5O2Apyh1XJmp8q82CZRz/ZzxKmFAeYgYE
                        |KPbzr/SeLkNvo9zaYZLMGT1Zle8pu7gBWF8DPFg1r77Y1zfSSRTRMSXQk0BVN5uR
                        |2Ru8A53ZI7yDOB73pNXbtV++XdBzbwzBDG24NY80o+bbGSCRgizeDqNBeVjzOzyf
                        |wRp6KFuLrwfksnUcWcwMBz3af6d5uh5hrDII63t30u3eVdmGYUb9oi5JjCOtcJta
                        |r3EhwoeEoeioAxpJebe0Q0OEbEICh4Z/oxGYaG/rn9UZ3Hhw9sdngihiTx/sQ8yg
                        |CGURXr/tQSw1knrmU7Fe1TytfcEhaGhnfjRXhUHXP75ycp4mdp3uRsHSKT7VN95H
                        |lCVxZGUMkE9w8CZQTH2RmL6E5r0VqilktViWmuf31h2DPzg9rvBj+rQpBvgQzUiv
                        |1TzuFzsuLKBp3KMpxHrnIxEMS2ERj1Kr7mAxW3xZVt3dYrw8SdbfozJ4x/d8ciKu
                        |ovN0BBrPIn0wS6v7hT2mMtneEG/xbXZFjL8XqVwIooRCDOhw4UfWb71CdpBNZ8ln
                        |tje4Ri0/C7l5ZJGYJNOpZFBlpDXmMTkCAwEAAaNTMFEwHQYDVR0OBBYEFHJaeKBJ
                        |FcPOMwPGxt8uNESLRJ2YMB8GA1UdIwQYMBaAFHJaeKBJFcPOMwPGxt8uNESLRJ2Y
                        |MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggIBAAjUW4YmUjYz6K50
                        |uuN/WT+vRtPAKjTcKPi397O0sa1EZDq7gJt2gbYBMyqDFyoivKeec2umXzm7n0o5
                        |yDJ1jwgl0ORxqtCmjzPuwbY9EL8dBycACsr8KorXct2vseC+uxNWnsLbUVs3iTbI
                        |AG5dtXpytZJXioVvR/Hi6DnJ8hP6wQLKJYw3E91jjIdfUBWT1GRzjTHo6VBxlvQd
                        |KFS8JeHMaUJjWiXeI8ZYPjLCDL2Fxs6hlgySBaZSbGySraFwt9l4RDVnUxexMloc
                        |ZECALfJg4fISgZodHXRxVBKEUv71ebSqYfJt8f8LeyfLVK/MY9rmpdV8DGQieaaV
                        |YdhslUYx6vTnk/0Q/LbeHXI2cm2qBP1oyPusydTWWc6TowCLhHqTJ+eAB2X/RjT/
                        |MTe/B3GGKgn1lgB37qF2hVDWtrDvNzE4OGQCNBR/iJDHz5+8MV+4FDT0/7ruTP0B
                        |iMDtuT7Jrk9O/UhAZyG4uyUm+kpcPIevGy2ZVQUgk/zIqLH+R4QrRebXRLrNsKuP
                        |o07htJltXDGDSekSDgK3OnZwLOyTUrz1zMmGqGbqRCwOQAWcZBWLrIjUjM0k9vPy
                        |qYUqf4FphVwX4JqDhm8JSS/et/0431MjMfQC/qauAhPBITgRjlDVEVvGB40aiNLk
                        |ootapja6lKOaIpqp0kmmYN7gFIhp
                        |-----END CERTIFICATE-----""".stripMargin

  val cert2: String = """-----BEGIN CERTIFICATE-----
                        |MIIFgTCCA2mgAwIBAgIUTOUJeR7kGBPch+AvEUcfL+fFP6wwDQYJKoZIhvcNAQEL
                        |BQAwUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlROMTQwMgYKCZImiZPyLGQBAQwk
                        |YjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1ZTU4MzEzMB4XDTE5MDcxMjE2
                        |MzEyOVoXDTI3MDkyODE2MzEyOVowUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlRO
                        |MTQwMgYKCZImiZPyLGQBAQwkYjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1
                        |ZTU4MzEzMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAzVWb3HUyZEnl
                        |zR9X4SZMORYmgw7iZZgs41cyfuskBX2dYa9m5MyQSUHkVGc5/pYPq0Qns9l8MJ7o
                        |ay+uQsg7ow36vViQm4hLZmkyCUs2BLdP28MiX1mHjWmYGqt+ZpkRgsZqYrGjwKzi
                        |5HA1IESMh0lPNAyKMbr0MUra+RdjijXvQAHGxRv1oHbrJsjBKsnscBx9/VIX8X3H
                        |J8i35rLwrij9O+Vl1t0z6UzAMSeLI5pFI6zuHifG66OJpcHqOq9WvG5Z5MVUspqe
                        |qmw+voI2UFFsbVy8q+RaIJt3Ogn6Z45iipkUSSZyAr3kbpj6XQmhpvL2/XBFfDcL
                        |7NKY1dPr4VV9NirtnVrk7XbOuOIRKptYYld+Dqolv03uBVO4Kx4jQc95aPyCxDeE
                        |0VCtDZCySITKCkgwQ861LeseCb2Vik+rvGO5QJ6Ssdo20WexjrCEIqWWsOc9KF0s
                        |ZI7gVeEOrR4+wBdvWZkBw6kJyG6gbR4yswxI/2DwS1sN0WZn83nozW2CdjKmQy18
                        |zXtP1Z3gMUY0YqQGsNG49kbf7nWjNHw+7rus6CcpmgyjDSkGrqNfgSn3JQSQn2FT
                        |+wazZ0t6DJxBB5HK7UywzA+0M+3q+RdSJ/WEH6u7famvMvxoiRA6M/ZbLINGdC/4
                        |omifV+i8xFEQWmosdhFx0QWounnIU3kCAwEAAaNTMFEwHQYDVR0OBBYEFByrvy4Y
                        |sNqPewRSj58sh4a7HkXTMB8GA1UdIwQYMBaAFByrvy4YsNqPewRSj58sh4a7HkXT
                        |MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggIBAHT2n8zbHMdRQwka
                        |/lOdEQVsE++Jr5EMtwCHN/OzHEzFAkCeZwrZPgx0gwbrCDDujsKaNhgqo+2xjpuA
                        |CmzjsanuubvK5/lJ3vVarB/6qvaWARGYAZd9RjDxS0OCL43rPOEzYN8qaAXp8M+Y
                        |EOOw8bQOy/eJEs2oSdJzTILIqAKsXiZpW1G//bVL+baY6KjgI/2ZAml0NWX5wUsT
                        |/JrEvookXW0FqqlW6ukyTyx4zsHxLFJ48ydcVsdOYwrhSvfx21H9f/T7s3XlX3vh
                        |9HBZAQULDbMiwMu2OOskmNTegsKUXaqyAgtHRqWYORlhMv3afW2Sy86CbLuwrlww
                        |U3ytQVfgEmdIba03IlxzHTJL4NQn6WsZyzRzgLxJf2eJ2ACf7RX/Lf3tjW0OI+sq
                        |gQ7QpOVFPHficPfhiQkoThvHoc2kGMWL/WkENTPrcUZ6bhwIqJ+orK6dL4NGiJiB
                        |TuHOsJOv21y7l90fnaenL5lyBkMWeHfzYQkhGfcUN/55yWkjkrOkgeFUqXkyQQ1v
                        |Jq19A1ObBOe7axqsHvqeW4GJapXBjcWFRsq2ltP19lefKu27ikMWzEvkYJu1TX7t
                        |RmA82uwwqMa4eo5lXYkLCkVLyN3sSb39vqMb9C45y2fjBZv9OcpWCt/c0FwKz00K
                        |TZEW7+Ri43DsMyRwYiCafuVThL+J
                        |-----END CERTIFICATE-----""".stripMargin

  val root:  CoreNodeFact =
    NodeConfigData.factRoot.modify(_.rudderAgent.securityToken).setTo(Certificate(NodeConfigData.ROOT_CERT))
  val node1: CoreNodeFact = NodeConfigData.fact1.modify(_.rudderAgent.securityToken).setTo(Certificate(cert1))
  val node2: CoreNodeFact = NodeConfigData.fact2.modify(_.rudderAgent.securityToken).setTo(Certificate(cert2))

  val nodes: Map[NodeId, CoreNodeFact] = (root :: node1 :: node2 :: Nil).map(x => (x.id, x)).toMap

  val dest: File = File("/tmp/rudder-test-allnodescerts.pem")

  dest.deleteOnExit()

  sequential // because files, etc

  // the content of the file is just each certificate concatenated together
  val expectedFileContent = NodeConfigData.ROOT_CERT + "\n" + cert1 + "\n" + cert2 + "\n"

  "When nodes have certificates, they" should {
    "certificate are written to file only for nodes with certificate" in {
      val writer = new WriteNodeCertificatesPemImpl(Some(s"/usr/bin/touch ${dest.pathAsString}"))
      val res    = ZioRuntime.runNow(writer.writeCertificates(dest, nodes).either)

      (res must beRight) and
      (dest.contentAsString(using StandardCharsets.UTF_8) must beEqualTo(expectedFileContent))
    }
  }

  "when there is an error in async, we shoud get a log" in {

    val writer = new WriteNodeCertificatesPemImpl(Some("/non/existing/command"))

    import scala.jdk.CollectionConverters.*

    val log = writer.logger.logEffect.asInstanceOf[ch.qos.logback.classic.Logger]

    val ctx     = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    import ch.qos.logback.classic.encoder.PatternLayoutEncoder
    val encoder = new PatternLayoutEncoder() // this is mandatory. Without that, nothing happen silently
    encoder.setContext(ctx) // order of setters matters
    encoder.setPattern("%msg%n")
    encoder.start()         // don't forget start!

    val os       = new ByteArrayOutputStream()
    val appender = new OutputStreamAppender[ILoggingEvent]()
    appender.setImmediateFlush(true)
    appender.setName("test-byte-array")
    appender.setContext(ctx)     // context must be set first
    appender.setEncoder(encoder) // then encoder
    appender.setOutputStream(os) // then output stream
    appender.start()             // don't forget start!

    // if you want to remove other appenders
    log.iteratorForAppenders().asScala.foreach { a =>
      log.detachAppender(a)
      a.stop()
    }

    log.setAdditive(false) // disable root logger/appender
    log.addAppender(appender)

    // exec
    writer.writeCerticatesAsync(dest, nodes)

    (new String(os.toByteArray, StandardCharsets.UTF_8) must beMatching(
      """(?s).*Unexpected: Error when executing reload command.*code: -2147483648.*""".r
    )
      .eventually(10, 100.millis.asScala)) and
    (dest.contentAsString(using StandardCharsets.UTF_8) must beEqualTo(expectedFileContent).eventually(10, 100.millis.asScala))
  }
}
