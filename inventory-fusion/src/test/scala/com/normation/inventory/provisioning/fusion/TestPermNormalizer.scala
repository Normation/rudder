/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.inventory.provisioning
package fusion

import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestKeyNormalizer {

  val sanitizer = new PrintedKeyNormalizer

  val reference = "MIIBCAKCAQEA50tCDX1wBHy3mQ1d15OI6K83Ep5PRWVBjRePrbReskmsVdniWIaU 4ijA6+yfJD90hB53D/lXcCj673kQaB0b+BXsyl58TGmz5dZmqVB576/78ch13/TW lixZ8IR9dMTNhEJXwveOEosae1Jazxq4Y0GWX9eP1xJrDRefErzwmMLnx0m3yzbb Eiygs2Y8xALmlW+uubu/A3o0Z4/IDEwbruepkB7Ug510UUvTW/E2CzPWXk22Lvok aUZJllqAHB/xL7XOhowBykQHRU5yk/Q6fZJ8XxdMbW++4jq6N45UXhie32bNdSn8 ZcGU20mwvP365CGj9vVRdI+vrPrOsStYjwIBIw=="
  val rsa_oneline = "-----BEGIN RSA PUBLIC KEY----- MIIBCAKCAQEA50tCDX1wBHy3mQ1d15OI6K83Ep5PRWVBjRePrbReskmsVdniWIaU 4ijA6+yfJD90hB53D/lXcCj673kQaB0b+BXsyl58TGmz5dZmqVB576/78ch13/TW lixZ8IR9dMTNhEJXwveOEosae1Jazxq4Y0GWX9eP1xJrDRefErzwmMLnx0m3yzbb Eiygs2Y8xALmlW+uubu/A3o0Z4/IDEwbruepkB7Ug510UUvTW/E2CzPWXk22Lvok aUZJllqAHB/xL7XOhowBykQHRU5yk/Q6fZJ8XxdMbW++4jq6N45UXhie32bNdSn8 ZcGU20mwvP365CGj9vVRdI+vrPrOsStYjwIBIw== -----END RSA PUBLIC KEY-----"
  val rsa_72col = """
            -----BEGIN RSA PUBLIC KEY-----
MIIBCAKCAQEA50tCDX1wBHy3mQ1d15OI6K83Ep5PRWVBjRePrbReskmsVdniWIaU 4ijA6+
yfJD90hB53D/lXcCj673kQaB0b+BXsyl58TGmz5dZmqVB576/78ch13/TW lixZ8IR9dMTN
hEJXwveOEosae1Jazxq4Y0GWX9eP1xJrDRefErzwmMLnx0m3yzbb Eiygs2Y8xALmlW+uub
u/A3o0Z4/IDEwbruepkB7Ug510UUvTW/E2CzPWXk22Lvok aUZJllqAHB/xL7XOhowBykQH
RU5yk/Q6fZJ8XxdMbW++4jq6N45UXhie32bNdSn8 ZcGU20mwvP365CGj9vVRdI+vrPrOsS
tYjwIBIw==
            -----END RSA PUBLIC KEY-----"""

  val pem_oneline = "-----BEGIN PEM----- MIIBCAKCAQEA50tCDX1wBHy3mQ1d15OI6K83Ep5PRWVBjRePrbReskmsVdniWIaU 4ijA6+yfJD90hB53D/lXcCj673kQaB0b+BXsyl58TGmz5dZmqVB576/78ch13/TW lixZ8IR9dMTNhEJXwveOEosae1Jazxq4Y0GWX9eP1xJrDRefErzwmMLnx0m3yzbb Eiygs2Y8xALmlW+uubu/A3o0Z4/IDEwbruepkB7Ug510UUvTW/E2CzPWXk22Lvok aUZJllqAHB/xL7XOhowBykQHRU5yk/Q6fZJ8XxdMbW++4jq6N45UXhie32bNdSn8 ZcGU20mwvP365CGj9vVRdI+vrPrOsStYjwIBIw== -----END PEM-----"
  val pem_72col = """
            -----BEGIN PEM-----
MIIBCAKCAQEA50tCDX1wBHy3mQ1d15OI6K83Ep5PRWVBjRePrbReskmsVdniWIaU 4ijA6+
yfJD90hB53D/lXcCj673kQaB0b+BXsyl58TGmz5dZmqVB576/78ch13/TW lixZ8IR9dMTN
hEJXwveOEosae1Jazxq4Y0GWX9eP1xJrDRefErzwmMLnx0m3yzbb Eiygs2Y8xALmlW+uub
u/A3o0Z4/IDEwbruepkB7Ug510UUvTW/E2CzPWXk22Lvok aUZJllqAHB/xL7XOhowBykQH
RU5yk/Q6fZJ8XxdMbW++4jq6N45UXhie32bNdSn8 ZcGU20mwvP365CGj9vVRdI+vrPrOsS
tYjwIBIw==
            -----END PEM-----"""

  val foo_oneline = "-----begin foo----- MIIBCAKCAQEA50tCDX1wBHy3mQ1d15OI6K83Ep5PRWVBjRePrbReskmsVdniWIaU 4ijA6+yfJD90hB53D/lXcCj673kQaB0b+BXsyl58TGmz5dZmqVB576/78ch13/TW lixZ8IR9dMTNhEJXwveOEosae1Jazxq4Y0GWX9eP1xJrDRefErzwmMLnx0m3yzbb Eiygs2Y8xALmlW+uubu/A3o0Z4/IDEwbruepkB7Ug510UUvTW/E2CzPWXk22Lvok aUZJllqAHB/xL7XOhowBykQHRU5yk/Q6fZJ8XxdMbW++4jq6N45UXhie32bNdSn8 ZcGU20mwvP365CGj9vVRdI+vrPrOsStYjwIBIw== -----end foo-----"
  val foo_80col = """
            -----BEGIN foo-----
MIIBCAKCAQEA50tCDX1wBHy3mQ1d15OI6K83Ep5PRWVBjRePrbReskmsVdniWIaU 4ijA6+yfJD90hB
53D/lXcCj673kQaB0b+BXsyl58TGmz5dZmqVB576/78ch13/TW lixZ8IR9dMTNhEJXwveOEosae1Ja
zxq4Y0GWX9eP1xJrDRefErzwmMLnx0m3yzbb Eiygs2Y8xALmlW+uubu/A3o0Z4/IDEwbruepkB7Ug5
10UUvTW/E2CzPWXk22Lvok aUZJllqAHB/xL7XOhowBykQHRU5yk/Q6fZJ8XxdMbW++4jq6N45UXhie
32bNdSn8 ZcGU20mwvP365CGj9vVRdI+vrPrOsStYjwIBIw==
            -----END foo-----"""




  @Test
  def testSanitize() {
    assertEquals(reference , sanitizer(rsa_oneline))
    assertEquals(reference , sanitizer(rsa_72col))
    assertEquals(reference , sanitizer(pem_oneline))
    assertEquals(reference , sanitizer(pem_72col))
    assertEquals(reference , sanitizer(foo_oneline))
    assertEquals(reference , sanitizer(foo_80col))
  }

}
