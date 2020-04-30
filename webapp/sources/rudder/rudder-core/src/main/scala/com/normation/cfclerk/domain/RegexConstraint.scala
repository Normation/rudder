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

package com.normation.cfclerk.domain
import java.util.regex.Pattern

import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import com.normation.rudder.services.policies.PropertyParserTokens
import com.normation.rudder.services.policies.PropertyParser

/**
 * We require a non empty regex pattern
 */
case class RegexConstraint(pattern: String, errorMsg: String) {

  if(pattern == null || pattern.isEmpty) {
    throw new ConstraintException("A regex constraint was created with an empty pattern, which has no meaning")
  }

  val compiled = Pattern.compile(pattern)

  /* throw a ConstraintException if the value doesn't match the pattern */
  def check(varValue: String, varName: String) : PureResult[String] = {
    // don't fail on interpolated values. That value can be in the middle of a string, and
    // we can't say much about the respect of the resulting expanded string regarding the regex,
    // so just accept and believe in our users
    if(compiled.matcher(varValue).matches) {
      Right(varValue)
    } else {
      PropertyParser.parse(varValue) match {
        case Left(err) => // most likely the user wanted to use a property but made a typo
          Left(Inconsistency(s"Please modify ${varName}: error while parsing property: ${varValue}")) // parser error are not hyper user friendly
        case Right(tokens) => // check that we don't have a pure string
          if(PropertyParserTokens.containsVariable(tokens)) {
            Right(varValue)
          } else {
            Left(Inconsistency(s"Please modify ${varName} to match the requested format ${if (errorMsg != "") " : " + errorMsg else ""}"))
          }
      }
    }
  }
}

object RegexConstraint {
  // (?i) : case insensitive
  val mail = """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,4}\b"""

  val ipv4 = """\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}
                     (?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b""".replaceAll("""\s""", "")

  //didn't invented it: http://sroze.io/2008/10/09/regex-ipv4-et-ipv6/
  val ipv6 ="""(
      (([0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}) |
      (([0-9A-Fa-f]{1,4}:){6}:[0-9A-Fa-f]{1,4}) |
      (([0-9A-Fa-f]{1,4}:){5}:([0-9A-Fa-f]{1,4}:)?[0-9A-Fa-f]{1,4}) |
      (([0-9A-Fa-f]{1,4}:){4}:([0-9A-Fa-f]{1,4}:){0,2}[0-9A-Fa-f]{1,4}) |
      (([0-9A-Fa-f]{1,4}:){3}:([0-9A-Fa-f]{1,4}:){0,3}[0-9A-Fa-f]{1,4}) |
      (([0-9A-Fa-f]{1,4}:){2}:([0-9A-Fa-f]{1,4}:){0,4}[0-9A-Fa-f]{1,4}) |
      (([0-9A-Fa-f]{1,4}:){6}((\b((25[0-5])|(1\d{2})|(2[0-4]\d)|(\d{1,2}))\b).){3}(\b((25[0-5])|(1\d{2})|(2[0-4]\d)|(\d{1,2}))\b)) |
      (([0-9A-Fa-f]{1,4}:){0,5}:((\b((25[0-5])|(1\d{2})|(2[0-4]\d)|(\d{1,2}))\b).){3}(\b((25[0-5])|(1\d{2})|(2[0-4]\d)|(\d{1,2}))\b)) |
      (([0-9A-Fa-f]{1,4}:){0,5}:((\b((25[0-5])|(1\d{2})|(2[0-4]\d)|(\d{1,2}))\b).){3}(\b((25[0-5])|(1\d{2})|(2[0-4]\d)|(\d{1,2}))\b)) |
      (::([0-9A-Fa-f]{1,4}:){0,5}((\b((25[0-5])|(1\d{2})|(2[0-4]\d)|(\d{1,2}))\b).){3}(\b((25[0-5])|(1\d{2})|(2[0-4]\d)|(\d{1,2}))\b)) |
      ([0-9A-Fa-f]{1,4}::([0-9A-Fa-f]{1,4}:){0,5}[0-9A-Fa-f]{1,4}) |
      (::([0-9A-Fa-f]{1,4}:){0,6}[0-9A-Fa-f]{1,4}) |
      (([0-9A-Fa-f]{1,4}:){1,7}:)
    )""".replaceAll("""\s""","")

  val ipv4or6 = s"""(${ipv4})|(${ipv6})"""

}

object MailRegex    extends RegexConstraint(RegexConstraint.mail   , "invalid format for the given email (valid example : pat@example.com)")

object Ipv4Regex    extends RegexConstraint(RegexConstraint.ipv4   , "invalid format for the given IPv4 (it should be xxx.xxx.xxx.xxx, where xxx is a number from 0-255)")
object Ipv6Regex    extends RegexConstraint(RegexConstraint.ipv6   , "invalid format for the given IPv6 (typically looks like 2001:db8::1:0:0:1, "+
                                                                     "see https://tools.ietf.org/html/rfc5952 for more information)")
object IpRegex      extends RegexConstraint(RegexConstraint.ipv4or6, "invalid format for the given IP v4 or v6 (IP v4: xxx.xxx.xxx.xxx, where xxx is a number from 0-255); "+
                                                                     "IP v6: typically looks like 2001:db8::1:0:0:1, see https://tools.ietf.org/html/rfc5952 for more information")
