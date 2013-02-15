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

import com.unboundid.ldap.sdk.{RDN,DN,Modification}
import com.unboundid.ldif.LDIFRecord
import com.normation.ldap.ldif.{ToLDIFString,ToLDIFRecords}
import scala.collection.mutable.{Buffer, Map => MutMap, HashMap, ObservableMap, Subscriber, Publisher}
import scala.collection.script._
import net.liftweb.common._
import com.normation.exceptions.TechnicalException

import LDAPTree._
/*
 * An LDAP tree of entries.
 * It's composed of a root entry and
 * children.
 * Each children is itself a tree.
 * When a child is added to a tree, the parent DN
 * of the root's child is set to the dn of the
 * tree's root.
 */
trait LDAPTree extends Tree[LDAPEntry] with ToLDIFRecords with ToLDIFString  {
  selfTree =>
  lazy val parentDn = root.parentDn

  //validation on children
  private var _childrenValidation = new Subscriber[Message[(RDN,LDAPTree)],ObservableMap[RDN,LDAPTree]]() {
    override def notify(pub: ObservableMap[RDN,LDAPTree],event: Message[(RDN, LDAPTree)]): Unit = {
      event match {
        case Update(loc,(rdn,tree)) =>
          require(root.optDn == tree.root.parentDn,
            "Bad child/parent DN : try to add children %s to entry %s".
              format(tree.root.dn,selfTree.root.dn))
          require(Some(rdn) == tree.root.rdn)
        case _ => //nothing
      }
    }
  }

  var _children = new HashMap[RDN,LDAPTree]() /*with UpdateChildTree*/ with ObservableMap[RDN,LDAPTree]
  // { override lazy val parentDn = selfTree.root.parentDn }

  _children.subscribe(_childrenValidation)

  override def children() = Map() ++ _children

  def addChild(child:LDAPTree) : Unit = {
    child.root.rdn match {
      case Some(r) =>
        _children += ((r,child))
        () // unit is expected
      case None => {
        throw new TechnicalException("Try to add a child Tree but the RDN of the root of this child is not defined. Parent: %s , child root: %s".
            format(root.dn, child.root))
      }
    }
  }

  def addChild(child:LDAPEntry) : Unit = addChild(LDAPTree(child))

  def addChildren(children:Seq[LDAPTree]) : Unit = children.foreach { t => addChild(t) }
  def setChildren(children:Seq[LDAPTree]) : Unit = {
    _children.clear
    children.foreach { t => addChild(t) }
  }
  def deleteChildren(rdns:Seq[RDN]) = rdns.foreach { r => _children -= r }

  override def addChild(rdn:RDN, child:Tree[LDAPEntry]) : Unit = {
    addChild(LDAPTree(child))
  }

  override def toLDIFRecords() : Seq[LDIFRecord] = {
    Seq(root.toLDIFRecord) ++ _children.valuesIterator.toSeq.flatMap( _.toLDIFRecords)
  }

  override def toString() = {
    val children = {
        if(_children.size > 0) {
          val c = _children.map{ case(k,v) => s"${k} -> ${v}" }
          s"children:{${c}}"
        } else {
          "no child"
        }
    }
    s"{${root.dn.toString}, ${children}}"
  }


  //not sure it's a really good idea. Hopefully, we won't mix LDAPTrees and LDAPEntries in HashSet...
  override def hashCode() = root.hashCode
  override def equals(that:Any) : Boolean = that match {
    case t:LDAPTree =>
      root == t.root &&
      _children.size == t._children.size &&
      _children.iterator.forall(e => e._2 == t._children(e._1))
    case _ => false
  }


  /*
   * Set opt on LDAPTree is forward to tree's root
   */
  def setOpt[A](a:Option[A], attributeName:String, f:A => String) : Unit =
     root.setOpt(a, attributeName, f)
}

//trait UpdateChildTree extends MutMap[RDN,LDAPTree] {
//  def parentDn : Option[DN]
//  abstract override def += (kv: (RDN,LDAPTree)): this.type = {
//    val (key, tree) = kv
//    if(tree.parentDn == this.parentDn) super.+=(kv)
//    else super.+=((key, LDAPTree(tree,this.parentDn)))
//  }
//}


object LDAPTree {
  //loggin
  val logger = org.slf4j.LoggerFactory.getLogger(classOf[LDAPTree])

  def apply(r:LDAPEntry,c:Traversable[(RDN,LDAPTree)]) : LDAPTree = new LDAPTree {
    require(null != r, "root of a tree can't be null")
    require(null != c, "children map of a tree can't be null")

    val root = r
    c foreach { x => _children += x }
  }

  def apply(r:LDAPEntry) : LDAPTree = apply(r, Map.empty[RDN,LDAPTree])

  /**
   * Copy an LDAPTree changing its parent dn
   *
   */
  def move(tree:LDAPTree, newRdn:Option[RDN] = None,newParentDn:Option[DN] = None) : LDAPTree = {
    val rdn = newRdn.orElse(tree.root.rdn)
    val parentDn = newParentDn.orElse(tree.root.parentDn)
    val newRoot = LDAPEntry(rdn,parentDn,tree.root.attributes.toSeq:_*)
    apply(newRoot,tree._children.map{ kv => (kv._1,LDAPTree.move(kv._2, newParentDn = newRoot.optDn))})
  }

  /*
   * copy children reference without any verification
   * from 'from' to 'to'
   */
  def overrideChildren(from:LDAPTree, to:LDAPTree) : Unit = {
    to._children = from._children
    to._children.subscribe(to._childrenValidation)
  }

  //transtype Tree[LDAPEntry] => LDAPTree
  def apply(tree:Tree[LDAPEntry]) : LDAPTree = apply(tree.root, tree.children.map( e => (e._1,apply(e._2))))

  import scala.collection.JavaConversions._

  /*
   * Build an LDAP tree from a list of entries.
   * If the list is empty, return None.
   * All entries in the list safe one (the one that will become the root of the tree)
   * must have a direct parent in other entries.
   */
  def apply(entries:Iterable[LDAPEntry]) : Box[LDAPTree] = {
    if(null == entries || entries.isEmpty) Empty
    //verify that there is no duplicates
    else if(entries.map(_.dn).toSet.size != entries.size) {
      val s = entries.map(_.dn).toSet
      val res = entries.map(_.dn).filter(x => ! s.contains(x))
      Failure(s"Some entries have the same dn, what is forbiden: ${res}")
    } else {
      val used = Buffer[DN]()
      /*
       * the iterable must be sorted on dn and only descendants of root.root.dn
       * - add the direct children of root
       * - return root with its children and not used entries
       */
      def recBuild(root:LDAPTree, possible:Seq[LDAPEntry]) : LDAPTree = {
        val directChildren = possible.filter(e => root.root.dn == e.dn.getParent)
        for(child <- directChildren) {
          used += child.dn
          root.addChild(recBuild(LDAPTree(child), possible.filter(e => child.dn.isAncestorOf(e.dn,false)) ))
        }
        root
      }

      val sorted = entries.toSeq.sortWith((x,y) => x.dn.compareTo( y.dn) < 0)
      val rootEntry = sorted.head
      val root = recBuild(LDAPTree(rootEntry), sorted.filter(e => rootEntry.dn.isAncestorOf(e.dn,false)))

      if(used.size < entries.size-1) {
        val s = entries.map(_.dn).filter(x => !used.contains(x))
        Failure(s"Some entries have no parents: ${s}")
      } else Full(root)
    }
  }
  /*
   * Compare two LDAP tree and return the list of modification to
   * apply to "old" to merge with "target".
   * The two roots HAVE to have the same DN, or None is returned.
   * The comparison is strict, so that:
   * - if
   */
  def diff(source:LDAPTree, target:LDAPTree, removeMissing:Boolean) :  Option[Tree[TreeModification]] = {
    if(source.root.dn != target.root.dn) {
      logger.debug("DN of the two LDAP tree's root are different: {} <> {}",source.root.dn,target.root.dn)
      None
    } else {
      //modification on root
      val rootDiff = LDAPEntry.merge(source.root, target.root, removeMissing = removeMissing)

      val mods:Tree[TreeModification] = if(rootDiff.isEmpty) Tree(NoMod) else Tree(Replace((source.root.dn,rootDiff)))

      val intersection = source.children.keySet intersect(target.children.keySet)

      //remove entries present in source but not in target
      for(k <- source.children.keySet -- intersection) {
        mods.addChild(k, Tree(Delete(source.children()(k).map(_.dn))))
      }

      //add entries present in target but not in source
      for(k <- target.children.keySet -- intersection) {
        mods.addChild(k, Tree(Add(target.children()(k))))
      }

      //diff all entries both in source and target
      for(k <- intersection) {
        diff(source.children()(k), target.children()(k), removeMissing) foreach { d =>
          mods.addChild(k, d)
        }
      }
      Some(mods)
    }
  }
}
