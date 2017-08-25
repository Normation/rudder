/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

package com.normation.license



/*
 * This file define what is a software version and how
 * it behaves.
 */

case class Version(
    major : Int
  , minor : Int
  , micro : Int
  , prefix : String = ""
  , suffix : String = ""
) extends Ordered[Version] {

  override def toString = prefix + major + "." + minor + "." + micro + suffix

  override def compare(other: Version) = {
    def or(a: Int, b: Int) = if(a == 0) b else a

    or(prefix.compare(other.prefix)
     , or(major.compare(other.major)
       , or(minor.compare(other.minor)
         , or(micro.compare(other.micro)
           , Version.suffixCompare(suffix, other.suffix)
    ))))
  }

}

object Version {

  /*
   * That method will create a plugin version from a string.
   * It may fails if the pattern is not one known for rudder plugin, which is:
   * (A.B-)x.y(.z)(post)
   * Where part between parenthesis are optionnal,
   * A,B,x,y,z are non-empty list of digits,
   * A.B are Rudder major.minor version,
   * x.y are plugin major.minor.micro version - if micro not specified, assumed to be 0,
   * post is any non blank/control char list (ex: ~alpha1)
   *
   */
  def from(version: String): Option[Version] = {
    //carefull: group matching nothing, like optionnal group, return null :(
    def nonNull(s: String) = s match {
      case null => ""
      case x    => x
    }

    val pattern = """(\d+\.\d+-)?(\d+)\.(\d+)(\.(\d+))?(\S+)?""".r.pattern
    val matcher = pattern.matcher(version)
    if( matcher.matches ) {
      val micro = matcher.group(5) match {
        case null | "" => 0
        case x  => x.toInt
      }
      Some(Version(
          matcher.group(2).toInt
        , matcher.group(3).toInt
        , micro
        , nonNull(matcher.group(1))
        , nonNull(matcher.group(6))
     ))
    } else {
      None
    }
  }

  // for suffix, "~" is lower than anything including empty
  // string. Empty string is lower than any other non empty string:
  // "~~zz" < "~plop" < "" < "-plop"
  def suffixCompare(s1: String, s2: String): Int = {
    def recSuffixCompare(l1: List[Char], l2: List[Char]): Int = {
      (l1, l2) match {
        case ('~' :: a, '~' :: b ) => recSuffixCompare(a, b)
        case ('~' :: _, _ )        => -1
        case (_       , '~' :: _ ) => 1
        case (x       , y        ) => x.mkString.compare(y.mkString)
      }
    }
    recSuffixCompare(s1.toList, s2.toList)
  }
}
