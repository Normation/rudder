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

package com.normation.utils

import scala.xml._
import net.liftweb.common._

import org.junit.Test
import org.junit._
import org.junit.Assert._

import junit.framework.TestSuite
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestXmlUtils {

  val a_n = "child_A"
  val b_n = "child_B"
  val c_n = "child_C"
  val x_n = "non existing X node"
  val subA_n = "subchild_A"
  val subB_n = "subchild_B"
  val subC_n = "subchild_C"


  def child_A(child:Node) = <child_A>{child}</child_A>
  def child_B(child:Node) = <child_B>{child}</child_B>
  val child_C = <child_C></child_C>

  val sub_A : Node = <subchild_A></subchild_A>
  val sub_B : Node = <subchild_B></subchild_B>
  val sub_C : Node = <subchild_C></subchild_C>

  val root : Node =
  <root>
    {child_A(sub_A)}
    {child_A(sub_B)}
    {child_A(child_B(sub_A))}
    {child_B(sub_A)}
    {child_C}
  </root>

  //utility fonction that return the failure message or "no message" if Empty
  private def msg[T](box:Box[T]) : String = box match {
    case Full(x) => "Full box containing " + x
    case Empty => "empty box"
    case Failure(m,_,_) => m
  }

  private def sameNodes(searchNodeName:String, target:Node, treeScope:Boolean) : Unit = {
    val test = XmlUtils.getUniqueNode(root, searchNodeName,treeScope)
    assertEquals(msg(test), Full(target), test)
  }

  private def emptyRequestRes(searchNodeName:String, treeScope:Boolean) : Unit = {
    val test = XmlUtils.getUniqueNode(root, searchNodeName,treeScope)
    assertTrue(msg(test), test.isEmpty)
  }

  @Test def testGetOne() : Unit = {

    //C is unique in the subtree
    sameNodes(c_n, child_C, false)
    sameNodes(c_n, child_C, true)

    //B is unique only at the first level
    sameNodes(b_n, child_B(sub_A), false)
    emptyRequestRes(b_n, true)

    //A is not unique at any scope
    emptyRequestRes(a_n, true)
    emptyRequestRes(a_n, false)

    //X does not exists
    emptyRequestRes(x_n, true)
    emptyRequestRes(x_n, false)

    //Sub A does not exist at first level and is not unique in subtree
    emptyRequestRes(subA_n, true)
    emptyRequestRes(subA_n, false)

    //sub B does not exist at first level but is unique in subtree
    emptyRequestRes(subB_n, false)
    sameNodes(subB_n, sub_B, true)

  }

}