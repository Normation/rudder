/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*************************************************************************************
*/

package com.normation.ldap.sdk

import org.junit.Test
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import com.normation.ldap.sdk.LDAPEntry._
import com.unboundid.ldap.sdk.{RDN,DN}
import DN.NULL_DN
import scala.collection.mutable.{Map => MutMap}

@RunWith(classOf[BlockJUnit4ClassRunner])
class LDAPTreeTest {

  val rdn1 = new RDN("dc=top")
  val dn1 = new DN(rdn1,NULL_DN)
  val rdn2 = new RDN("ou=middle")
  val dn2 = new DN(rdn2,dn1)
  val rdn3 = new RDN("cn=child")
  val dn3 = new DN(rdn3, dn2)

  @Test
  def testAddSubChild() {

    object tree extends LDAPTree {
      top =>
      override val root = LDAPEntry(dn1)

      val c1 = new LDAPTree() {
        tree_c1 =>
        override val root = LDAPEntry(dn2)

        top.addChild(tree_c1)
      }

      lazy val c1children = top._children(rdn2)

    }

    assertEquals(dn1,tree.root.dn)
    assertEquals(dn2,tree.c1.root.dn)
    println("tree: " + tree)
    tree.c1children.addChild(LDAPTree(LDAPEntry(dn3)))

    println("add1 " + tree.toLDIFString())
    tree.c1children.addChild(LDAPTree(LDAPEntry(dn3)))
    println("add2 " + tree.toLDIFString())

  }


  @Test
  def buildTreeFromEntries() {
    val rdn4 = new RDN("cn=child2")
    val entries = Seq(
        LDAPEntry(dn2),
        LDAPEntry(dn1),
        LDAPEntry(dn3),
        LDAPEntry(new DN(rdn4,dn2))
    )

    val optTree = LDAPTree(entries)
    assertTrue(optTree.isDefined)
    val tree = optTree.get
    assertEquals(1,tree._children .size)
    val mTree = tree._children(rdn2)
    assertEquals(LDAPEntry(dn2), mTree.root)
    assertEquals(2, mTree.children.size )
    assertEquals(LDAPEntry(dn3), mTree._children(rdn3).root)
    assertEquals(LDAPEntry(new DN(rdn4,dn2)), mTree._children(rdn4).root)
  }
}
