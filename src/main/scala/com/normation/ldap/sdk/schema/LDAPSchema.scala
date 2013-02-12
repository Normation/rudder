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
package schema
import com.normation.exceptions.TechnicalException


/**
 * An object that stores all the object classes known
 * by  the application and gives utilities methods
 * on them, like "find parents", "find children".
 * Class name are case insensitive when used as
 * method parameter of that class.
 *
 * TODO: use/connect to com.unboundid.ldap.sdk.schema.Schema
 */
class LDAPSchema {
  type S = scala.collection.Set[LDAPObjectClass]
  import scala.collection.mutable.{Map => MutMap}
  def S = scala.collection.Set[LDAPObjectClass] _

  /**
   * map of class name (lower case) -> object class
   */
  private val ocs = MutMap("top" -> LDAPObjectClass.TOP)
  /**
   * A map of an object class name -> direct sub classes
   */
  private val childrenReg = MutMap[String, Set[String]]()
  private val parentsReg = MutMap[String, List[String]]("top" -> List())

  /**
   * Get the matching ObjectClass, or throw a NoSuchElementException
   * if the key does not match any ObjectClass
   */
  def apply(className:String) = ocs.getOrElse(className.toLowerCase, throw new NoSuchElementException("Missing LDAP Object in the Schema: %s".format(className)))

  /**
   * Optionaly get the ObjectClass whose name is className
   */
  def get(className:String) = ocs.get(className.toLowerCase)

  /**
   * Register a new object class.
   * The sup, if different from top, must already be registered.
   * If the same object class already exists, just ignore the query.
   * If an object class with the same name but different properties
   * (sup, must or may) already exists, throws an error.
   */
  def +=(oc:LDAPObjectClass) : LDAPSchema = {
    require(null != oc)
    val key = oc.name.toLowerCase
    val pKey = oc.sup.name.toLowerCase
    ocs.get(pKey) match {
      case None => throw new TechnicalException("Can not register object class %s because its parent class %s is not yet registerd".format(oc.name,oc.sup.name))
      case Some(p) => ocs.get(key) match {
        case Some(x) if(x != oc) => throw new TechnicalException("""Can not register object class %s because an other different object class with same name was already registerd.
                                     | existing: %s
                                     | new     : %s""".stripMargin('|').format(oc.name, x, oc))
        case _ => {
          ocs += ((key,oc))
          childrenReg(pKey) = childrenReg.getOrElse(pKey,Set()) + key
          parentsReg(key) = pKey :: parentsReg(pKey)
        }
      }
    }
    this
  }

  /**
   * Register a new object class
   */
  def +=(
      name : String,
      sup : LDAPObjectClass = LDAPObjectClass.TOP,
      must: Set[String] = Set(),
      may : Set[String] = Set()
  ) : LDAPSchema = +=(new LDAPObjectClass(name, sup, must, may))

  /**
   * Returned the set of children object classes for the
   * given object class
   */
  def children(objectClass:String) : Set[String] = childrenReg.getOrElse(objectClass.toLowerCase,Set())

  /**
   * Returned the list of parent object classes for the
   * given object class (head of list is the direct parent)
   */
  def parents(objectClass:String) : List[String] = parentsReg(objectClass.toLowerCase)


  def objectClassNames(objectClass:String) : List[String] = apply(objectClass).name :: parents(objectClass)

  def objectClasses(objectClass:String) : LDAPObjectClasses =
    LDAPObjectClasses(this.objectClassNames(objectClass).map(apply(_)):_*)

  /**
   * Given a set of object classes which are ALL registered,
   * retrieve the smallest subset of independant class hierarchy.
   *
   * For example, if we have :
   * C sup B sup A sup top
   * D sup A sup top
   * F sup E sup top
   *
   * And we pass the Set(top, A,B,C,D,E,F)
   * it returns Set(C,D,F)
   *
   * @param name
   * @return
   */
  def demux(names : String*) : S = {
    require(null != names)
    val ns : Seq[String] = names.map( _.toLowerCase).distinct;
    require(ns.forall(x => ocs.isDefinedAt(x)), "One of the given names is not registered: " + names)
    val lists:List[List[String]] = ns.map(x => x :: this.parents(x)).toList

    //list have to be ordered from shortest to longest
    def demuxRec(names:List[List[String]], acc:List[String]) : List[String] = names match {
      case Nil => acc
      case h::tail => if(tail.exists(l => l.contains(h.head))) demuxRec(tail,acc) else demuxRec(tail, h.head::acc)
    }
    demuxRec(lists.sortWith(_.size < _.size), Nil).map(this(_)).toSet
  }

  def unapply(name:String) : Option[LDAPObjectClass] = ocs.get(name)
}

