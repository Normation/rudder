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
import zio.Chunk

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
 *
 * In the case of two identical sequence of same digits, the most precise is always the
 * highest one, 1.0 < 1.0.0 (and of course, 1.0 < 1.0.1 and 1.0.0 < 1.0.1)
 * This is what most people (strangly) expect, and so that comparison is consistant with
 * case class `equals` (because these objects are structuraly different)
 */

final case class Version(epoch: Long, head: PartType, parts: List[VersionPart]) extends ToVersionString with Ordered[Version] {
  def toVersionString: String =
    (if (epoch == 0) "" else epoch.toString + ":") + toVersionStringNoEpoch

  def toVersionStringNoEpoch = head.toVersionString + parts.map(_.toVersionString).mkString("")

  override def compare(other: Version): Int = Version.compare(this, other)
}

object Version {
  // create a list of default value, ie a list of ".0", of the given size
  def defaultList(size: Int) = List.fill(size)(VersionPart.After(Separator.Dot, PartType.Numeric(0)))
  @scala.annotation.tailrec
  def compareList(a: List[VersionPart], aInitSize: Int, b: List[VersionPart], bInitSize: Int): Int = {
    (a, b) match {
      case (Nil, Nil)           =>
        aInitSize - bInitSize
      case (Nil, l)             => compareList(defaultList(l.size), aInitSize, l, bInitSize)
      case (l, Nil)             => compareList(l, aInitSize, defaultList(l.size), bInitSize)
      case (h1 :: t1, h2 :: t2) =>
        val h = VersionPart.compare(h1, h2)
        if (h == 0) compareList(t1, aInitSize, t2, bInitSize) else h
    }
  }
  def compare(a: Version, b: Version):                                                         Int = {
    val e = a.epoch - b.epoch
    if (e == 0L) {
      val h = PartType.compare(a.head, b.head)
      if (h == 0) {
        compareList(a.parts, a.parts.size, b.parts, b.parts.size)
      } else h
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
  case object Tilde extends Separator { def index = 0; def toVersionString: String = "~" }
  case object Minus extends Separator { def index = 1; def toVersionString: String = "-" }
  case object Plus  extends Separator { def index = 1; def toVersionString: String = "+" }
  case object Comma extends Separator { def index = 1; def toVersionString: String = "," }
  // None is same as "." to allow both "1.0 == 1.0.0" and "1.a == 1a"
  case object None  extends Separator { def index = 2; def toVersionString: String = ""  }
  case object Dot   extends Separator { def index = 2; def toVersionString: String = "." }

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
  // snapshot < nightly < alpha < beta < milestone < rc < [0-9] < other

  // we keep original name in value
  final case class Snapshot(value: String)       extends PartType { def index = 0; def toVersionString: String = value          }
  final case class Nightly(value: String)        extends PartType { def index = 1; def toVersionString: String = value          }
  final case class Alpha(value: String)          extends PartType { def index = 2; def toVersionString: String = value          }
  final case class Beta(value: String)           extends PartType { def index = 3; def toVersionString: String = value          }
  final case class Milestone(value: String)      extends PartType { def index = 4; def toVersionString: String = value          }
  final case class RC(value: String)             extends PartType { def index = 5; def toVersionString: String = value          }
  final case class Numeric private (value: Long) extends PartType { def index = 6; def toVersionString: String = value.toString }
  final case class Chars(value: String)          extends PartType { def index = 7; def toVersionString: String = value          }

  object Numeric {
    // with software in node, we have zillion of duplication of numeric of low ran, so we store them here:
    val cacheSize = 100
    val numCache  = Chunk.fromIterable((0 until cacheSize).map(i => new Numeric(i.toLong)))
    def apply(n: Long): Numeric = if (n < cacheSize) numCache(n.toInt) else new Numeric(n)
  }
  def compare(a: PartType, b: PartType): Int = {
    val d = a.index - b.index
    if (d == 0) {
      (a, b) match {
        case (Numeric(na), Numeric(nb)) => (na - nb).sign.toInt
        case (Chars(aa), Chars(ab))     => String.CASE_INSENSITIVE_ORDER.compare(aa, ab)
        case _                          => 0
      }
    } else d
  }
}

sealed trait VersionPart extends ToVersionString with Ordered[VersionPart] {
  def separator: Separator
  def value:     PartType

  override def toVersionString:             String = separator.toVersionString + value.toVersionString
  override def compare(other: VersionPart): Int    = VersionPart.compare(this, other)
}

object VersionPart {
  final case class Before(separator: Separator, value: PartType)
      extends VersionPart // we can have before with "-" separator for ex with alpha, etc
  final case class After(separator: Separator, value: PartType) extends VersionPart

  def compare(a: VersionPart, b: VersionPart) = (a, b) match {
    case (_: Before, _: After) => -1
    case (_: After, _: Before) => 1
    case (a, b)                =>
      val c = Separator.compare(a.separator, b.separator) // not sure? Does 1.0~alpha < 1.0.alpha ?
      if (c == 0) PartType.compare(a.value, b.value) else c
  }
}

object ParseVersion {
  import fastparse._
  import fastparse.NoWhitespace._

  def ascii                  = Charset.forName("US-ASCII").newEncoder()
  // chars allowed in a version. Only ascii, non control, non space, non separator - including ":" used for epoch
  def versionChar(c: Char)   = ascii.canEncode(c) && !(c.isDigit || c.isControl || c.isSpaceChar || separatorChar(c) || c == ':')
  def separatorChar(c: Char) = List('~', '+', ',', '-', '.').contains(c)

  def num[A: P]   = P(CharIn("0-9").rep(1).!.map(_.toLong))
  def chars[A: P] = P(CharsWhile(versionChar).rep(1).!).map { s =>
    import PartType._
    s.toLowerCase match {
      case "snapshot"  => Snapshot(s)
      case "nightly"   => Nightly(s)
      case "alpha"     => Alpha(s)
      case "beta"      => Beta(s)
      case "milestone" => Milestone(s)
      case "rc"        => RC(s)
      case _           => Chars(s)
    }
  }

  def epoch[A: P]          = P(num ~ ":")
  def toSeparator(c: Char) = {
    c match {
      case '~' => Separator.Tilde
      case '-' => Separator.Minus
      case '+' => Separator.Plus
      case ',' => Separator.Comma
      case '.' => Separator.Dot
    }
  }
  def separators[A: P]     = P(CharsWhile(separatorChar).!).map((s: String) => s.toSeq.map(toSeparator))

  def listOfSepToPart(list: List[Separator]): List[VersionPart]    = {
    list.map {
      case Separator.Tilde => VersionPart.Before(Separator.Tilde, PartType.Chars(""))
      case sep             => VersionPart.After(sep, PartType.Chars(""))
    }
  }
  def numPart[A: P]:                          P[List[VersionPart]] = P(separators ~ num).map {
    case (seq, n) => // seq is at least 1
      seq.last match {
        case Separator.Tilde =>
          listOfSepToPart(seq.init.toList) ::: VersionPart.Before(Separator.Tilde, PartType.Numeric(n)) :: Nil
        case sep             => listOfSepToPart(seq.init.toList) ::: VersionPart.After(sep, PartType.Numeric(n)) :: Nil
      }
  }

  def charPart[A: P]: P[List[VersionPart]] = P(separators ~ chars).map {
    case (seq, n) => // seq is at least 1
      (seq.last, n) match {
        case (Separator.Tilde, s)     => listOfSepToPart(seq.init.toList) ::: VersionPart.Before(Separator.Tilde, s) :: Nil
        case (sep, c: PartType.Chars) => listOfSepToPart(seq.init.toList) ::: VersionPart.After(sep, c) :: Nil
        case (sep, prerelease)        => listOfSepToPart(seq.init.toList) ::: VersionPart.Before(sep, prerelease) :: Nil
      }
  }

  def noSepPart1[A: P] = P(chars).map(c => VersionPart.After(Separator.None, c) :: Nil)
  def noSepPart2[A: P] = P(num).map(n => VersionPart.After(Separator.None, PartType.Numeric(n)) :: Nil)

  def startNum[A: P] = P(num).map(i => PartType.Numeric(i))

  def version[A: P] = P(
    Start ~ epoch.? ~/ startNum ~/
    (numPart | charPart | noSepPart1 | noSepPart2).rep(0) ~ separators.? ~ End
  ).map {
    case (e, head, list, opt) =>
      Version(e.getOrElse(0L), head, list.flatten.toList ::: listOfSepToPart(opt.toList.flatten))
  }

  def parse(s: String): Either[String, Version] = {
    fastparse.parse(s, version(_)) match {
      case Parsed.Success(value, index) => Right(value)
      case _: Parsed.Failure =>
        Left(
          s"Error when parsing '${s}' as a version. Only ascii (non-control, non-space) chars are allowed in a version string."
        )
    }
  }

}
