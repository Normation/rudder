package com.normation

import _root_.zio.json.*
import _root_.zio.json.ast.Json
import org.specs2.execute.FailureDetails
import org.specs2.matcher.EqualityMatcher
import org.specs2.matcher.Expectable
import org.specs2.matcher.Matcher
import org.specs2.matcher.MatchFailure
import org.specs2.matcher.MatchResult
import org.specs2.matcher.MustMatchers
import org.specs2.matcher.StandardMatchResults
import org.specs2.mutable.Specification

trait JsonSpecMatcher { self: MustMatchers & Specification =>

  import com.normation.JsonSpecMatcher.*

  /**
      * Tests for a non-strict equality of json, by comparing the string.
      * Ignores any whitespace between words, non-words, and lines
      * i.e. additional whitespace in either side would still make the test pass.
      *
      * Displays the actual and expected json in the error message in a pretty format.
      */
  def equalsJson(res: String): Matcher[String] = {
    new EqualityMatcher(res)
      .adapt(cleanParse, koFunction = _ + "\neven when removing whitespaces")
  }

  def equalsJsonSemantic(res: String): Matcher[String] = {
    res.fromJson[Json] match {
      case Right(json_) =>
        new JsonEqualityMatcher(json_).toStringMatcher
      case Left(_)      => (s: String) => ko(s"The provided json is not valid, cannot do semantic comparison of $s with $res")
    }
  }
}

private object JsonSpecMatcher {
  // we choose a custom message using the pretty-rendered json
  class JsonEqualityMatcher(t: Json) extends EqualityMatcher[Json](t) { outer =>

    /**
     * Compares both "pretty" format version of json
     */
    def toStringMatcher: EqualityMatcher[String] = {
      new EqualityMatcher[String](t.toJsonPretty) {
        override def apply[S <: String](s: Expectable[S]): MatchResult[S] = {
          val checkedValues =
            s"\n\nParsed actual json: '${prettyPrint(s.value)}'\n  Expected: '${prettyPrint(t)}'\nwith semantic comparison"
          result(jsonMatchResult(s).updateMessage(_ + checkedValues), s)
        }
      }
    }

    private def stringAsJson(s: Expectable[String]) = s.value.fromJson[Json] match {
      case Left(_)      =>
        StandardMatchResults
          .ko(s"The provided json is not valid, cannot do semantic comparison of $s with ${prettyPrint(t)}")
      case Right(value) =>
        this.apply[Json](s.map(_ => value))
    }

    // intercept the match (json against json) to display 'expected' and 'actual' with prettifed format
    private def jsonMatchResult(s: Expectable[String]): MatchResult[Any] = stringAsJson(s) match {
      case MatchFailure(ok, ko, expectable, trace, FailureDetails(actual, expected)) =>
        MatchFailure.create(ok(), ko(), expectable, trace, FailureDetails(prettyPrint(actual), prettyPrint(expected)))
      case other                                                                     => other
    }
  }

  def cleanParse(text: String): String = {
    val cleaned = text.replaceAll("(\\w)\\s+", "$1").replaceAll("(\\W)\\s+", "$1").replaceAll("\\s+$", "")
    prettyPrint(cleaned)
  }

  def prettyPrint(json: String): String = {
    json.fromJson[Json].map(_.toJsonPretty).getOrElse(json)
  }
  def prettyPrint(json: Json):   String = {
    json.toJsonPretty
  }
}
