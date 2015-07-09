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

package com.normation.cfclerk.domain
import com.normation.utils.HashcodeCaching

/**
 * When comparing two version numbers, first the epoch of each are compared, then
 * the upstream_version if epoch is equal. epoch is compared numerically. The
 * upstream_version part is compared using the following algorithm :
 *
 * The strings are compared from left to right.
 *
 * First the initial part of each string consisting entirely of non-digit
 * characters is determined. These two parts (one of which may be empty) are
 * compared lexically. If a difference is found it is returned. The lexical
 * comparison is a comparison of ASCII values modified so that all the letters sort
 * earlier than all the non-letters and so that a tilde sorts before anything, even
 * the end of a part. For example, the following parts are in sorted order from
 * earliest to latest: ~~, ~~a, ~, the empty part, a.
 *
 * Then the initial part of the remainder of each string which consists entirely of
 * digit characters is determined. The numerical values of these two parts are
 * compared, and any difference found is returned as the result of the
 * comparison. For these purposes an empty string (which can only occur at the end
 * of one or both version strings being compared) counts as zero.
 *
 * These two steps (comparing and removing initial non-digit strings and initial
 * digit strings) are repeated until a difference is found or both strings are
 * exhausted.
 */

class TechniqueVersionFormatException(msg: String) extends Exception("The version format of a technique should be : [epoch:]upstream_version\n" + msg)

final class TechniqueVersion(val epoch: Int, val upsreamTechniqueVersion: UpstreamTechniqueVersion) extends Ordered[TechniqueVersion] {
  def compare(v: TechniqueVersion): Int = {
    if (epoch != v.epoch) epoch compare v.epoch
    else upsreamTechniqueVersion compare v.upsreamTechniqueVersion
  }

  override def equals(other:Any) : Boolean = other match {
    case that:TechniqueVersion => this.epoch == that.epoch && this.upsreamTechniqueVersion == that.upsreamTechniqueVersion
    case _ => false
  }

  override lazy val hashCode : Int = 7 + 13 * epoch + 41 * upsreamTechniqueVersion.hashCode

  override lazy val toString = {
    if(epoch < 1) upsreamTechniqueVersion.value
    else epoch.toString + ":" + upsreamTechniqueVersion.value
  }
}

object TechniqueVersion {

  /*  epoch :This is a single (generally small) unsigned integer. It may be omitted, in
   *  which case zero is assumed(value=0). If it is omitted then the upstream_version may
   *  not contain any colons.
   */
  def splitEpochUpstream(value: String): (Int, UpstreamTechniqueVersion) = {
    val arr = value.split(":")
    if (arr.length <= 1) return (0, UpstreamTechniqueVersion(value))

    val errorEx = new TechniqueVersionFormatException("The epoch value has to be an unsigned integer which is not the case of : " + arr(0))
    try {
      val epochVal = arr(0).toInt
      if (epochVal < 0) throw errorEx
      return (epochVal, UpstreamTechniqueVersion(rest(value, arr(0) + ":")))
    } catch {
      case e: NumberFormatException => throw errorEx
    }
  }

  def rest(hole: String, pref: String) = hole.substring(pref.length, hole.length)

  def apply(value: String) = {
    val (epoch, upsreamTechniqueVersion) = splitEpochUpstream(value)
    new TechniqueVersion(epoch, upsreamTechniqueVersion)
  }
}

case class UpstreamTechniqueVersion(value: String) extends Ordered[UpstreamTechniqueVersion] with HashcodeCaching {
  checkValid(value)

  import scala.util.matching.Regex
  import TechniqueVersion.rest

  def checkValid(strings: String*) {
    for (value <- strings) {
      if (value.length == 0 || !value(0).isDigit)
        throw new TechniqueVersionFormatException("The upstream_version should start with a digit : " + value)

      val validReg = new Regex("[A-Za-z0-9.+\\-:~]*")
      validReg.findPrefixOf(value) match {
        case Some(matchReg) if (matchReg != value) =>
          throw new TechniqueVersionFormatException("The upstream_version contains invalid charaters.\n" +
            "The upstream_version may contain only alphanumerics and " +
            "the characters . + - : ~ (full stop, plus, hyphen, colon, tilde).")
        case _ =>
      }
    }
  }

  val intsReg = new Regex("[0-9]+")

  def compare(upsreamTechniqueVersion: UpstreamTechniqueVersion): Int = {
    val (s1, s2) = (value, upsreamTechniqueVersion.value)

    (intsReg.findPrefixOf(value), intsReg.findPrefixOf(s2)) match {
      case (Some(subInt1), Some(subInt2)) =>
        val cmpI = subInt1.toInt compare subInt2.toInt
        if (cmpI != 0)
          cmpI
        else compareRest(rest(s1, subInt1), rest(s2, subInt2))
      case (Some(_), _) => 1
      case (_, Some(_)) => -1
      case _ => 0
    }
  }

  val noIntsReg = new Regex("[^0-9]*")

  def compareRest(s1: String, s2: String): Int = {
    val (sub1, sub2) = (noIntsReg.findPrefixOf(s1).get, noIntsReg.findPrefixOf(s2).get)
    val cmpS = compareStrings(sub1.toList, sub2.toList)

    // if a difference is found (or if the end is reached), return it
    if (cmpS != 0 || (s1.length == sub1.length && s2.length == sub2.length))
      cmpS
    else {
      val (restS1, restS2) = (rest(s1, sub1), rest(s2, sub2))
      val cmpI = compareIntegers(restS1, restS2)
      if (cmpI != 0 || sub1.size == 0) cmpI
      else compareRest(restS1, restS2)
    }
  }

  def compareStrings(s1: List[Char], s2: List[Char]): Int = {
    (s1, s2) match {
      case (h1 :: t1, h2 :: t2) => {
        val (cv1, cv2) = (CharVersion(h1), CharVersion(h2))
        if (cv1 == cv2) compareStrings(t1, t2)
        else cv1 compare cv2
      }
      case (h1, h2 :: t2) =>
        if (h2 == '~') 1
        else -1
      case (h1 :: t1, h2) =>
        if (h1 == '~') -1
        else 1
      case (h1, h2) => 0
    }
  }

  def compareIntegers(s1: String, s2: String): Int = {
    (intsReg.findPrefixOf(s1), intsReg.findPrefixOf(s2)) match {
      case (Some(i1), Some(i2)) => i1.toInt compare i2.toInt
      case (Some(_), _) => 1
      case (_, Some(_)) => -1
      case _ => 0
    }
  }

  private case class CharVersion(c: Char) extends Ordered[CharVersion] with HashcodeCaching {
    def compare(cv: CharVersion): Int = {
      if (c == cv.c) 0
      else if (c == '~' && cv.c != '~') -1
      else if (c != '~' && cv.c == '~') 1
      else if (c.isLetter && !cv.c.isLetter) -1
      else if (!c.isLetter && cv.c.isLetter) 1
      else c compare cv.c
    }
  }
}




