/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.inventory.domain

import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.json.*

/**
 * Test properties about agent type, especially regarding serialisation
 */
@RunWith(classOf[JUnitRunner])
class AgentTypesTest extends Specification {

  // test serialisation of agent type and security token.

  /*
   * In 4.1 and before, we add two separated attribute:
   * - agentType for agent type and version
   * - publicKey to store node RSA public key in pkcs1 format
   *
   * Starting with 4.2 and up, we use an unified field to store both
   * agent version and security token. The security token can be
   * a certificate of just a public key. In all cases, it's a json
   * with format:
   * {
   *   "agentType":"agent-type"
   * , "version"  : "version"
   * , "securityToken": {
   *     "value": "-----BEGIN RSA PUBLIC KEY-----\nMIIBCgKCA...."
   *   , "type" : "publicKey | certificate"
   *   }
   * }
   *
   */

  "Parsing agent type" should {
    "works for an agent without capabilities in 8.0 format" in {
      val cert = {
        """-----BEGIN CERTIFICATE-----\nMIIFTjCCAzagAwIBAgIUfa0+S+CyJahRzuwNNOFLNQQjIH8wDQYJKoZIhvcNAQEL\nBQAwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MB4XDTI0MDMwMTExMDIxM1oXDTM0\nMDIyNzExMDIxM1owFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MIICIjANBgkqhkiG\n9w0BAQEFAAOCAg8AMIICCgKCAgEAsmBUYI1A5vqkOTCW24m/mQhBaRu4WaAUXDAr\nArdIAAMN0KyHhEXP1/X32Flw90E0VjtUH4P+DYLBozXYWhJrUxdWLyn3TRSbv3Kr\npXoCWhMhMp8OK2s/mG+rfiMpTXIwaMhPnBaJFNV3c+bkijGAMHtFjl3+leXJvNZ7\nw3bIg4cA3e77kz7EWMyqxOUvvLMyY6wpd03ahe/By+iLgtOkgUwl9hMqMU8tJeaz\nNIeUporsHk5rrk8bSf6Mxxdknm43Sk6oflnueNCIUFdd4rS2JLieMugsTh8n/oH+\nk29ZyirE3ikhftmZ3vY8GQ3IcIzaXwiAOGnCKcze79zVx5jmOTGSZitGZaU/cZc6\nLjzEp+ZDmE8caVIksiA+hIlaZeBXNHB+YRv/gV1Rbt7kS1am+XUZJSfVFf+99YqE\nlZ5p0hqJsczkBb2RuMxWxxsWO3pesPUNCuL/yaeggTIp7eK06tQWi1EfXr40ctrf\noimbzMAKNBpRKWruL7652SlF75Usaq+PaPi1TtqYQjLRZmbpr+IR4uMeKnEMIZPw\n3dLDKBV6d71XkTAalCmcJU+fgYrRmgz1dEnaZDIXY+f+fYR15hsFpDrZ0avHgkzJ\nca/nT/rKeX7136BttxVSbZaTU9hnmjAvl+v0BF+JUWPQ3VPTcrmyUNAICt8xdVae\nuo1tsvkCAwEAAaOBkTCBjjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSRG0uHQRIA\ngl91YqzSjaeaw+F7PDBSBgNVHSMESzBJgBSRG0uHQRIAgl91YqzSjaeaw+F7PKEb\npBkwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3ghR9rT5L4LIlqFHO7A004Us1BCMg\nfzALBgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBABR2vTH/6WlqjaTZ/hQc\nB+crqRlFimqCiVTRdX6qfkt0tLX2dK6scHNTBT04ORLjLTAn0KbcOUz3k6i0mIqB\nnG5UjqCFEQR+i2V4Hz7aK6+7LBQuwXWRhDhvO5xMn7MxjvP0LGgNf5iiA4r/N22L\nEMc1prDESIOGmWdKg9XlfLxd877R2d8/3hXyT82Y2uJHO25b53skj4pbUOWLsGSw\nd4FBNnWqM+7Hbg2v9xFvmtfs2G2Inqk4Xjtjnj8qkVk3ft6KzClUIMXGXQ8QqaRp\nYPkTJm5g2UBYDiguD/tlz3VmbsNYU6fkap7DKqhttbaseIx1zDwdAv4jtVOWDyjx\nWq4b7pljYiczfRa84X/9v1x05pT3raELy5udY+Pxmnz+hOXOM+jY5bzSKS24b8rS\n5Sklmutm0IdflMKd5vNrXd9yPFLu3QzN50ArzHHXczwBLgjaMlZsPAp1wITlqM7+\nWCjy3qM5/KgAjH3L24MPTq23o9PokBVh1NH7lesZqgJPgsj+OG7FMQDvKzg7ytrs\nQlIDF1c8Ko+9/RrnRVAS8C4GZOqbmMmfJjMp09GBz2d0ixlTusF6m6iwfIVMf/nI\nP/V0D7STRiV62cfnZ3e0w8kIeZwWAgXI7RMHJU3skLyurUu8yxkp635IQyzQsW2A\nYo7t7O7fxjqD9yVI2QfkERZ7\n-----END CERTIFICATE-----\n"""
      }

      val json80 =
        s"""{"agentType":"cfengine-community","version":"8.0.0","securityToken":{"type":"certificate","value":"${cert}"}}"""
      val res    = json80.fromJson[AgentInfo]
      res must beRight(
        AgentInfo(
          AgentType.CfeCommunity,
          Some(AgentVersion("8.0.0")),
          Certificate(cert.replaceAll("""\\n""", "\n")), // this is needed because the json format writes the \n literaly
          Set()
        )
      )
    }

    "works for an agent with capabilities in 8.0 format" in {
      val cert = {
        """-----BEGIN CERTIFICATE-----\nMIIFTjCCAzagAwIBAgIUfa0+S+CyJahRzuwNNOFLNQQjIH8wDQYJKoZIhvcNAQEL\nBQAwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MB4XDTI0MDMwMTExMDIxM1oXDTM0\nMDIyNzExMDIxM1owFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MIICIjANBgkqhkiG\n9w0BAQEFAAOCAg8AMIICCgKCAgEAsmBUYI1A5vqkOTCW24m/mQhBaRu4WaAUXDAr\nArdIAAMN0KyHhEXP1/X32Flw90E0VjtUH4P+DYLBozXYWhJrUxdWLyn3TRSbv3Kr\npXoCWhMhMp8OK2s/mG+rfiMpTXIwaMhPnBaJFNV3c+bkijGAMHtFjl3+leXJvNZ7\nw3bIg4cA3e77kz7EWMyqxOUvvLMyY6wpd03ahe/By+iLgtOkgUwl9hMqMU8tJeaz\nNIeUporsHk5rrk8bSf6Mxxdknm43Sk6oflnueNCIUFdd4rS2JLieMugsTh8n/oH+\nk29ZyirE3ikhftmZ3vY8GQ3IcIzaXwiAOGnCKcze79zVx5jmOTGSZitGZaU/cZc6\nLjzEp+ZDmE8caVIksiA+hIlaZeBXNHB+YRv/gV1Rbt7kS1am+XUZJSfVFf+99YqE\nlZ5p0hqJsczkBb2RuMxWxxsWO3pesPUNCuL/yaeggTIp7eK06tQWi1EfXr40ctrf\noimbzMAKNBpRKWruL7652SlF75Usaq+PaPi1TtqYQjLRZmbpr+IR4uMeKnEMIZPw\n3dLDKBV6d71XkTAalCmcJU+fgYrRmgz1dEnaZDIXY+f+fYR15hsFpDrZ0avHgkzJ\nca/nT/rKeX7136BttxVSbZaTU9hnmjAvl+v0BF+JUWPQ3VPTcrmyUNAICt8xdVae\nuo1tsvkCAwEAAaOBkTCBjjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSRG0uHQRIA\ngl91YqzSjaeaw+F7PDBSBgNVHSMESzBJgBSRG0uHQRIAgl91YqzSjaeaw+F7PKEb\npBkwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3ghR9rT5L4LIlqFHO7A004Us1BCMg\nfzALBgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBABR2vTH/6WlqjaTZ/hQc\nB+crqRlFimqCiVTRdX6qfkt0tLX2dK6scHNTBT04ORLjLTAn0KbcOUz3k6i0mIqB\nnG5UjqCFEQR+i2V4Hz7aK6+7LBQuwXWRhDhvO5xMn7MxjvP0LGgNf5iiA4r/N22L\nEMc1prDESIOGmWdKg9XlfLxd877R2d8/3hXyT82Y2uJHO25b53skj4pbUOWLsGSw\nd4FBNnWqM+7Hbg2v9xFvmtfs2G2Inqk4Xjtjnj8qkVk3ft6KzClUIMXGXQ8QqaRp\nYPkTJm5g2UBYDiguD/tlz3VmbsNYU6fkap7DKqhttbaseIx1zDwdAv4jtVOWDyjx\nWq4b7pljYiczfRa84X/9v1x05pT3raELy5udY+Pxmnz+hOXOM+jY5bzSKS24b8rS\n5Sklmutm0IdflMKd5vNrXd9yPFLu3QzN50ArzHHXczwBLgjaMlZsPAp1wITlqM7+\nWCjy3qM5/KgAjH3L24MPTq23o9PokBVh1NH7lesZqgJPgsj+OG7FMQDvKzg7ytrs\nQlIDF1c8Ko+9/RrnRVAS8C4GZOqbmMmfJjMp09GBz2d0ixlTusF6m6iwfIVMf/nI\nP/V0D7STRiV62cfnZ3e0w8kIeZwWAgXI7RMHJU3skLyurUu8yxkp635IQyzQsW2A\nYo7t7O7fxjqD9yVI2QfkERZ7\n-----END CERTIFICATE-----\n"""
      }

      val json80 =
        s"""{"agentType":"cfengine-community","version":"8.0.0","securityToken":{"type":"certificate","value":"${cert}"},"capabilities":["acl","cfengine","curl","http_reporting","jq","xml","yaml"]}"""
      val res    = json80.fromJson[AgentInfo] match {
        case Left(err) => throw new RuntimeException(s"bad result: ${err}")
        case Right(x)  => x
      }
      val ser    = res.toJson
      res must beEqualTo(
        AgentInfo(
          AgentType.CfeCommunity,
          Some(AgentVersion("8.0.0")),
          Certificate(cert.replaceAll("""\\n""", "\n")), // this is needed because the json format writes the \n literaly
          Set("acl", "cfengine", "curl", "http_reporting", "jq", "xml", "yaml").map(AgentCapability.apply)
        )
      ) and ser === json80
    }
  }

}
