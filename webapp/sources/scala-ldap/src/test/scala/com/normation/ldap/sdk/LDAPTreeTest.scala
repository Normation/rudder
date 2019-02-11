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

import com.unboundid.ldap.sdk.{RDN,DN}
import DN.NULL_DN
import com.normation.zio._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner


@RunWith(classOf[JUnitRunner])
class LDAPTreeTest extends Specification {

  val rdn1 = new RDN("dc=top")
  val dn1 = new DN(rdn1,NULL_DN)
  val rdn2 = new RDN("ou=middle")
  val dn2 = new DN(rdn2,dn1)
  val rdn3 = new RDN("cn=child")
  val dn3 = new DN(rdn3, dn2)


  "ADding children in an LDAPTree" should {
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

    println("tree: " + tree)
    tree.c1children.addChild(LDAPTree(LDAPEntry(dn3)))

    println("add1 " + tree.toLDIFString())
    tree.c1children.addChild(LDAPTree(LDAPEntry(dn3)))
    println("add2 " + tree.toLDIFString())

    "have the correct dn" in {
      dn1 must beEqualTo(tree.root.dn) and (
        dn2 must beEqualTo(tree.c1.root.dn)
      )
    }
  }


  "Building a tree from entries should lead to the correct result" >> {
    val rdn4 = new RDN("cn=child2")
    val entries = Seq(
        LDAPEntry(dn2),
        LDAPEntry(dn1),
        LDAPEntry(dn3),
        LDAPEntry(new DN(rdn4,dn2))
    )

    val optTree = ZioRuntime.unsafeRun(LDAPTree(entries).either)
    val tree = optTree.getOrElse(throw new IllegalArgumentException("this is for test"))
    val mTree = tree._children(rdn2)


    (optTree must beRight[LDAPTree]) and
    (tree._children.size.toLong must beEqualTo(1l)                         ) and
    (mTree.root                 must beEqualTo(LDAPEntry(dn2))             ) and
    (mTree.children.size.toLong must beEqualTo(2l)                         ) and
    (mTree._children(rdn3).root must beEqualTo(LDAPEntry(dn3))             ) and
    (mTree._children(rdn4).root must beEqualTo(LDAPEntry(new DN(rdn4,dn2))))
  }
}
