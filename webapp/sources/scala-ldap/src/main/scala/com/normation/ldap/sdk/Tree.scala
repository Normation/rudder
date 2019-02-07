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

import com.unboundid.ldap.sdk.RDN

/*
 * A simple non variant tree structure to map LDAP
 * directory (that why children keys are RDNs and not only comparable)
 * root is immutable.
 * chidren are mutable
 * TODO : look in scala.collection.interfaces.TraversableMethods
 * to see interesting methods to implements here
 */
trait Tree[A] {

  def root() : A

  /*
   * Children of the root.
   */
  def children : Map[RDN,Tree[A]]

  def hasChildren = children.nonEmpty


  def addChild(rdn:RDN,child:Tree[A]) : Unit

  /*  ********
   * Traversable methods
   */
  def foreach[U](f: A => U) : Unit = {
    f(root)
    children.foreach(c => c._2.foreach(f) )
  }

  def map[B](f:A => B) : Tree[B] =
    Tree(f(root), children.map(e => (e._1, e._2.map(f))) )

//  def flatMap[B](f:A => Option[B]) : Option[Tree[B]] = f(root) match {
//    case None => None
//    case Some(r) =>  {
//      val m = scala.collection.mutable.Map[RDN,Tree[B]]()
//      for(e <- children.iterator) {
//        e._2.flatMap(f) match {
//          case None => ;
//          case Some(t) => m += (e._1 -> t)
//        }
//      }
//      Some(Tree(r, m.asInstanceOf[Map[RDN,Tree[B]]]))
//    }
//  }

  def toSeq() : Seq[A] = Seq(root) ++ children.flatMap(e => e._2.toSeq)
}

object Tree {
  def apply[X](r:X,c:Traversable[(RDN,Tree[X])]) : Tree[X] = new Tree[X] {
    require(null != r, "root of a tree can't be null")
    require(null != c, "children map of a tree can't be null")

    val root = r
    var children = Map[RDN,Tree[X]]() ++ c

    override def addChild(rdn:RDN,child:Tree[X]) : Unit = children += ((rdn,child))
  }

  def apply[X](r:X) : Tree[X] = apply(r,Seq[(RDN,Tree[X])]())

}
