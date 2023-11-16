package com.normation

import _root_.zio.json._
import _root_.zio.json.ast.Json
import org.specs2.matcher.Matcher
import org.specs2.matcher.MustMatchers
import org.specs2.mutable.Specification

trait JsonSpecMatcher { self: MustMatchers with Specification =>

  /**
      * Tests for a non-strict equality of json, by comparing the string.
      * Ignores any whitespace between words, non-words, and lines 
      * i.e. additional whitespace in either side would still make the test pass.
      * 
      * Displays the actual and expected json in the error message in a pretty format.
      */
  def equalsJson(res: String):                 Matcher[String] = {
    (
      (s: String) => cleanParse(s) == cleanParse(res),
      (s: String) => s"'${prettyPrint(s)}' did not equal '${prettyPrint(res)}'\neven when removing whitespaces"
    )
  }
  // add the aka manually to the error message
  def equalsJsonAka(res: String, aka: String): Matcher[String] = {
    (
      (s: String) => cleanParse(s) == cleanParse(res),
      (s: String) => s"$aka: '${prettyPrint(s)}' did not equal '${prettyPrint(res)}'\neven when removing whitespaces"
    )
  }

  def equalsJsonSemantic(res: String): Matcher[String] = {
    (
      (s: String) => s.fromJson[Json] == res.fromJson[Json],
      (s: String) => s"'${prettyPrint(s)}' did not semantically equal '${prettyPrint(res)}'"
    )
  }

  def equalsJsonSemanticAka(res: String, aka: String): Matcher[String] = {
    (
      (s: String) => s.fromJson[Json] == res.fromJson[Json],
      (s: String) => s"$aka: '${prettyPrint(s)}' did not semantically equal '${prettyPrint(res)}'"
    )
  }

  private def cleanParse(text: String) = {
    val cleaned = text.replaceAll("(\\w)\\s+", "$1").replaceAll("(\\W)\\s+", "$1").replaceAll("\\s+$", "")
    prettyPrint(cleaned)
  }

  private def prettyPrint(json: String) = {
    json.fromJson[Json].map(_.toJsonPretty).getOrElse(json)
  }
}
