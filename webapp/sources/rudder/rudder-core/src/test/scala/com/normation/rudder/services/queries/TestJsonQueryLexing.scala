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

package com.normation.rudder.services.queries

import com.normation.rudder.domain.queries.QueryReturnType.NodeReturnType
import net.liftweb.common.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

/*
 * Test query parsing.
 *
 * These test doesn't test JSON syntax error, as we rely on
 * a JSON parser for that part.
 */

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestJsonQueryLexing {

  val lexer: JsonQueryLexer = new JsonQueryLexer() {}

  val valid1_0 =
    """
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
]}
"""

  val valid1_1 = // order is irrelevant
    """
{  "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
], "select":"node", "composition":"or"}
"""

  val valid2_0 = // get all server
    """
{  "select":"node", "composition":"or", "where":[] }
"""

  val valid2_1 = // get all server
    """
{  "select":"node", "composition":"or" }
"""

  val valid3_0 = // composition may be empty, and if so is considered not defined
    """
{  "select":"node", "composition":"", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
] }
"""
  val valid3_1 = // get all server
    """
{  "select":"node", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
] }
"""
  val valid4_0 = // get all server
    """
{  "select":"node", "where":[] }
"""

  val valid4_1 = // get all server
    """
{  "select":"node" }
"""

  val valid5_0 = // invert, get none
    """
{  "select":"node", "transform":"invert", "where":[] }
"""

  val noSelectDefaultToNode =
    """
{  "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
]}
"""

  val errorMissing2_0 =
    """
{  "select":"node", "composition":"or", "where":[
  { "attribute":"speed", "comparator":"gteq"   , "value":"2000" }
]}
"""

  val errorMissing2_1 =
    """
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "comparator":"gteq"   , "value":"2000" }
]}
"""

  val errorMissing2_2 =
    """
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "value":"2000" }
]}
"""

  val errorEmpty1       =
    """
{  "select":"", "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
]}
"""
  val okEmptyObjectName =
    """
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":""   , "attribute":"ram"  , "comparator":"exists" }
]}
"""
  val errorEmpty2_1     =
    """
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
]}
"""
  val okComparatorEmpty =
    """
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"" }
]}
"""

  @Test
  def basicLexing(): Unit = {
    val where = List(
      StringCriterionLine("processor", "speed", "gteq", Some("2000")),
      StringCriterionLine("node", "ram", "exists")
    )

    val query = StringQuery(NodeReturnType, Some("or"), None, where)

    assertEquals(Full(query), lexer.lex(valid1_0))
    assertEquals(Full(query), lexer.lex(valid1_1))

    assertEquals(Full(StringQuery(NodeReturnType, Some("or"), None, Nil)), lexer.lex(valid2_0))
    assertEquals(Full(StringQuery(NodeReturnType, Some("or"), None, Nil)), lexer.lex(valid2_1))

    assertEquals(Full(StringQuery(NodeReturnType, None, None, where)), lexer.lex(valid3_0))
    assertEquals(Full(StringQuery(NodeReturnType, None, None, where)), lexer.lex(valid3_1))

    assertEquals(Full(StringQuery(NodeReturnType, None, None, Nil)), lexer.lex(valid4_0))
    assertEquals(Full(StringQuery(NodeReturnType, None, None, Nil)), lexer.lex(valid4_1))

    assertEquals(Full(StringQuery(NodeReturnType, None, Some("invert"), Nil)), lexer.lex(valid5_0))

    assertEquals(lexer.lex(noSelectDefaultToNode).map(_.returnType), Full(NodeReturnType))

    assertFalse(lexer.lex(errorMissing2_0).isDefined)
    assertFalse(lexer.lex(errorMissing2_1).isDefined)
    assertFalse(lexer.lex(errorMissing2_2).isDefined)

    assertFalse(lexer.lex(errorEmpty1).isDefined)

    assertTrue(lexer.lex(okEmptyObjectName).isDefined) // ok, we parse after that with known objects

    assertFalse(lexer.lex(errorEmpty2_1).isDefined)
    assertTrue(lexer.lex(okComparatorEmpty).isDefined) // ok, it's checked for each object latter on
  }
}
