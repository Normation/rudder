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

package com.normation.rudder.services.queries

import com.normation.rudder.services.queries._
import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import net.liftweb.common._
import com.normation.rudder.domain._
import com.normation.rudder.services.queries._
import com.normation.rudder.domain.queries.NodeReturnType


/*
 * Test query parsing.
 * 
 * These test doesn't test JSON syntax error, as we rely on
 * a JSON parser for that part.
 */

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestJsonQueryLexing {

  val lexer = new JsonQueryLexer() {}
  
  val valid1_0 = 
"""
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
]}  
"""  

  val valid1_1 = //order is irrelevant
"""
{  "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
], "select":"node", "composition":"or"}  
"""  
    
  val valid2_0 = //get all server
"""
{  "select":"node", "composition":"or", "where":[] }  
"""  
  
  val valid2_1 = //get all server
"""
{  "select":"node", "composition":"or" }  
"""  
    
  val valid3_0 = //composition may be empty, and if so is considered not defined
"""
{  "select":"node", "composition":"", "where":[ 
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
] }  
"""  
  val valid3_1 = //get all server
"""
{  "select":"node", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
] }  
"""  
  val valid4_0 = //get all server
"""
{  "select":"node", "where":[] }  
"""  
    
  val valid4_1 = //get all server
"""
{  "select":"node" }  
"""  
    
  val errorMissing1 = 
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
    
  val errorEmpty1 = 
"""
{  "select":"", "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
]}  
"""  
  val errorEmpty2_0 = 
"""
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":""   , "attribute":"ram"  , "comparator":"exists" }
]}  
"""  
  val errorEmpty2_1 = 
"""
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"exists" }
]}  
"""  
  val errorEmpty2_2 = 
"""
{  "select":"node", "composition":"or", "where":[
  { "objectType":"processor", "attribute":"speed", "comparator":"gteq"   , "value":"2000" },
  { "objectType":"node"   , "attribute":"ram"  , "comparator":"" }
]}  
"""  
    
  @Test
  def basicLexing() {
    val where = Seq(
        StringCriterionLine("processor", "speed", "gteq", Some("2000")),
        StringCriterionLine("node", "ram", "exists")
      )
      
    val query = StringQuery(NodeReturnType,Some("or"), where)
      
    assertEquals(Full(query), lexer.lex(valid1_0))
    assertEquals(Full(query), lexer.lex(valid1_1))
    
    assertEquals(Full(StringQuery(NodeReturnType,Some("or"), Seq())), lexer.lex(valid2_0) )
    assertEquals(Full(StringQuery(NodeReturnType,Some("or"), Seq())), lexer.lex(valid2_1) )
    
    assertEquals(Full(StringQuery(NodeReturnType,None, where)), lexer.lex(valid3_0) )
    assertEquals(Full(StringQuery(NodeReturnType,None, where)), lexer.lex(valid3_1) )
    
    assertEquals(Full(StringQuery(NodeReturnType,None, Seq())), lexer.lex(valid4_0) )
    assertEquals(Full(StringQuery(NodeReturnType,None, Seq())), lexer.lex(valid4_1) )
    
    assertFalse(lexer.lex(errorMissing1).isDefined)
    assertFalse(lexer.lex(errorMissing2_0).isDefined)
    assertFalse(lexer.lex(errorMissing2_1).isDefined)
    assertFalse(lexer.lex(errorMissing2_2).isDefined)
    
    assertFalse(lexer.lex(errorEmpty1).isDefined)
    assertFalse(lexer.lex(errorEmpty2_0).isDefined)
    assertFalse(lexer.lex(errorEmpty2_1).isDefined)
    assertFalse(lexer.lex(errorEmpty2_2).isDefined)
  }
}
