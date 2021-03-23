/*
*************************************************************************************
* Copyright 2020 Normation SAS
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

package com.normation.utils

import java.nio.charset.Charset

/*
 * A version is composed of :
 * - an optionnal Epoch: a natural number followed by ":"
 * - a list of version parts seperated by "." or "-" or "~"
 * - a change from letters to digits define a version part as if
 *   separated with a "-", "1.2.2sp1" == "1.2.2-sp1"
 * - "." is higher than "-" so that "1.2.2" > "1.2-2"
 * - "~" modify the following part to mean "before what precede"
 * - some words also mean "before", they are, sorted in that order:
 *   snapshot[s], nightly|nightlies, alpha, beta, milestone, rc
 *   so that "1.2-alpha1" == "1.2~alpha-1"
 * - all other words mean "after", and they are sorted alphabetically
 */
final case class Version(epoch: Long, head: PartType, parts: List[VersionPart]) extends ToVersionString with Ordered[Version] {
  def toVersionString: String =
    (if(epoch == 0) "" else epoch.toString + ":") + toVersionStringNoEpoch

  def toVersionStringNoEpoch = head.toVersionString + parts.map(_.toVersionString).mkString("")

  override def compare(other: Version): Int = Version.compare(this, other)
}

object Version {
  //create a list of default value, ie a list of ".0", of the given size
  def defaultList(size: Int) = List.fill(size)(VersionPart.After(Separator.Dot, PartType.Numeric(0)))
  @scala.annotation.tailrec
  def compareList(a: List[VersionPart], b: List[VersionPart]): Int = {
    (a, b) match {
      case (Nil, Nil) => 0
      case (Nil, l  ) => compareList(defaultList(l.size), l)
      case (l  , Nil) => compareList(l, defaultList(l.size))
      case (h1::t1, h2::t2) =>
        val h = VersionPart.compare(h1, h2)
        if(h == 0) compareList(t1, t2) else h
    }
  }
  def compare(a: Version, b: Version): Int = {
    val e = a.epoch - b.epoch
    if(e == 0L) {
      val h = PartType.compare(a.head, b.head)
      if(h == 0) compareList(a.parts, b.parts)
      else h
    } else e.sign.toInt
  }
}

sealed trait ToVersionString {
  def toVersionString: String
}

/*
 * Separator are ordered '~' , ''|'-', '.'
 */
sealed trait Separator extends ToVersionString with Ordered[Separator] {
  def index: Int
  override def compare(other: Separator): Int = Separator.compare(this, other)
}
object Separator {
  final case object Tilde extends Separator { def index= 0; def toVersionString: String = "~" }
  final case object Minus extends Separator { def index= 1; def toVersionString: String = "-" }
  final case object Plus  extends Separator { def index= 1; def toVersionString: String = "+" }
  final case object Comma extends Separator { def index= 1; def toVersionString: String = "," }
  // None is same as "." to allow both "1.0 == 1.0.0" and "1.a == 1a"
  final case object None  extends Separator { def index= 2; def toVersionString: String = ""  }
  final case object Dot   extends Separator { def index= 2; def toVersionString: String = "." }

  def compare(a: Separator, b: Separator): Int = a.index - b.index
}

/*
 * For parts, alpha is before number, so that:
 * 1.2.0 < 1.2.something
 * APART for the case were we have known name (alpha, etc) which are specifically sorted
 */
sealed trait PartType extends ToVersionString with Ordered[PartType] {
  def index: Int
  override def compare(other: PartType): Int = PartType.compare(this, other)
}
object PartType {
  //snapshot < nightly < alpha < beta < milestone < rc < [0-9] < other

  // we keep original name in value
  final case class Snapshot (value: String) extends PartType { def index = 0; def toVersionString: String = value }
  final case class Nightly  (value: String) extends PartType { def index = 1; def toVersionString: String = value }
  final case class Alpha    (value: String) extends PartType { def index = 2; def toVersionString: String = value }
  final case class Beta     (value: String) extends PartType { def index = 3; def toVersionString: String = value }
  final case class Milestone(value: String) extends PartType { def index = 4; def toVersionString: String = value }
  final case class RC       (value: String) extends PartType { def index = 5; def toVersionString: String = value }
  final case class Numeric  (value: Long  ) extends PartType { def index = 6; def toVersionString: String = value.toString }
  final case class Chars    (value: String) extends PartType { def index = 7; def toVersionString: String = value}

  def compare(a: PartType, b: PartType): Int = {
    val d = a.index - b.index
    if(d == 0) {
      (a, b) match {
        case (Numeric(na), Numeric(nb)) => (na - nb).sign.toInt
        case (Chars(aa)  , Chars(ab)  ) => String.CASE_INSENSITIVE_ORDER.compare(aa, ab)
      case _ => 0
    }
    } else d
  }
  }

sealed trait VersionPart extends ToVersionString with Ordered[VersionPart] {
  def separator: Separator
  def value    : PartType

  override def toVersionString: String = separator.toVersionString + value.toVersionString
  override def compare(other: VersionPart): Int = VersionPart.compare(this, other)
  }
object VersionPart {
  final case class Before(separator: Separator, value: PartType) extends VersionPart //we can have before with "-" separator for ex with alpha, etc
  final case class After (separator: Separator, value: PartType) extends VersionPart

  def compare(a: VersionPart, b: VersionPart) = (a, b) match {
    case (_: Before, _: After ) => -1
    case (_: After , _: Before) =>  1
    case (a, b)                 =>
      val c = Separator.compare(a.separator, b.separator) // not sure? Does 1.0~alpha < 1.0.alpha ?
      if(c == 0) PartType.compare(a.value, b.value) else c
    }
  }

object ParseVersion {
  import fastparse._, NoWhitespace._

  def ascii = Charset.forName("US-ASCII").newEncoder()
  // chars allowed in a version. Only ascii, non control, non space, non separator - including ":" used for epoch
  def versionChar(c: Char) = ascii.canEncode(c) && !(c.isDigit || c.isControl || c.isSpaceChar || separatorChar(c) || c == ':')
  def separatorChar(c: Char) = List('~', '+', ',', '-', '.').contains(c)

  def num[_ :P] = P(CharIn("0-9").rep(1).!.map(_.toLong))
  def chars[_ : P] = P( CharsWhile(versionChar).rep(1).! ).map { s =>
    import PartType._
    s.toLowerCase match {
      case "snapshot"  => Snapshot(s)
      case "nightly"   => Nightly(s)
      case "alpha"     => Alpha(s)
      case "beta"      => Beta(s)
      case "milestone" => Milestone(s)
      case "rc"        => RC(s)
      case _           => Chars(s)
  }}

  def epoch[_:P] = P( num ~ ":")
  def toSeparator(c: Char) = { c match {
    case '~' => Separator.Tilde
    case '-' => Separator.Minus
    case '+' => Separator.Plus
    case ',' => Separator.Comma
    case '.' => Separator.Dot
  }}
  def separators[_:P] = P( CharsWhile(separatorChar).! ).map { (s: String) =>
    s.toSeq.map(toSeparator)
  }

  def listOfSepToPart(list: List[Separator]): List[VersionPart] = { list.map {
    case Separator.Tilde => VersionPart.Before(Separator.Tilde, PartType.Chars(""))
    case sep             => VersionPart.After(sep, PartType.Chars(""))
  } }
  def numPart[_:P]: P[List[VersionPart]] = P( separators ~ num).map { case (seq, n) => // seq is at least 1
    seq.last match {
      case Separator.Tilde => listOfSepToPart(seq.init.toList) ::: VersionPart.Before(Separator.Tilde, PartType.Numeric(n)) :: Nil
      case sep             => listOfSepToPart(seq.init.toList) ::: VersionPart.After (sep            , PartType.Numeric(n)) :: Nil
  }}

  def charPart[_:P]: P[List[VersionPart]] = P( separators ~ chars).map { case (seq, n) => // seq is at least 1
    (seq.last, n) match {
      case (Separator.Tilde, s)                => listOfSepToPart(seq.init.toList) ::: VersionPart.Before(Separator.Tilde, s) :: Nil
      case (sep            , c:PartType.Chars) => listOfSepToPart(seq.init.toList) ::: VersionPart.After(sep, c)              :: Nil
      case (sep            , prerelease      ) => listOfSepToPart(seq.init.toList) ::: VersionPart.Before(sep, prerelease)    :: Nil
  }}

  def noSepPart1[_:P] = P( chars ).map { c =>
      VersionPart.After(Separator.None, c) :: Nil
  }
  def noSepPart2[_:P] = P( num ).map { n =>
      VersionPart.After(Separator.None, PartType.Numeric(n)) :: Nil
  }

  def startNum[_:P] = P( num ).map(PartType.Numeric)

  def version[_ :P] = P( Start ~ epoch.? ~/ (startNum | chars) ~/
                         (numPart | charPart | noSepPart1 | noSepPart2).rep(0) ~ separators.? ~ End).map {
    case (e, head, list, opt) =>
      Version(e.getOrElse(0L), head, list.flatten.toList ::: listOfSepToPart(opt.toList.flatten))
  }


  def parse(s: String): Either[String, Version] = {
    fastparse.parse(s, version(_)) match {
      case Parsed.Success(value, index) => Right(value)
      case _:Parsed.Failure => Left(s"Error when parsing '${s}' as a version. Only ascii (non-control, non-space) chars are allowed in a version string.")
    }
  }

}
