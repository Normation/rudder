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

import com.unboundid.ldap.sdk.schema.Schema
import com.unboundid.ldap.sdk.{Attribute, DN, Modification, RDN}
import DN.NULL_DN
import com.unboundid.ldif.LDIFRecord

import scala.jdk.CollectionConverters._
import scala.collection.mutable.Buffer
import org.slf4j.LoggerFactory
import com.normation.ldap.sdk.syntax._
import zio.Chunk

/**
 * A Scala facade for LDAP Entry.
 * - entries DN are immutable
 * - the RDN attribute is set automatically
 * - all attribute related method are Option (none are allowed to return null)
 * - we avoid String for dn/rdn
 *
 * That constructor should never been called from the client code,
 * factory methods in LDAPEntry should be.
 *
 * Typical use case:
 * val e = LDAPEntry("cn=foo, dc=org")
 * val e_dn = e.dn // return a DN object
 *
 * //Here, we are changing the e var, each time a new LDAPEntry
 * //is created.
 * e += new Attribute("sn", "bar1") //add value
 * e += new Attribute("sn", "bar2") //add value
 * e -= ("sn", "bar1") //remove value
 * e -= ("sn", "bar2") //remove value, it's the last, remove attribute
 *
 * e += new Attribute("sn", "bar1") //add value
 * e += new Attribute("sn", "bar2") //add value
 * e -= "sn" //remove attribute
 *
 * //could have been e += new Attribute("sn", "bar1", "bar2")
 * e +=! ("sn", "bar") //set attribute value to "bar" (remove other)
 *
 * val optionSurname = e("sn") //return Some(first value) of "sn" if sn is defined, None else
 *
 */
class LDAPEntry(private val _backed: UnboundidEntry) {

  //copy constructor, needed for special case, when mixing LDAPEntry with other types
  def this(e:LDAPEntry) = this(new UnboundidEntry(e._backed.getParsedDN, e._backed.getAttributes.asScala.toSeq:_*))

  //define a read only view of the backed entry, usefull in ldap connection to not have to override everything defined by Unboundid
  def backed = new com.unboundid.ldap.sdk.ReadOnlyEntry(_backed)

  //////  Helping methods to manipulate DN/RDN  //////

  lazy val optDn: Option[DN] = if(NULL_DN == dn) None else Some(dn)

  private[this] lazy val listRdns : List[RDN] = dn.getRDNs.toList

  private val _parentDn : Option[DN] = listRdns match {
    case r::Nil => None
    case r::p => Some(new DN(p.toSeq:_*))
    case _ => None
  }

  private val _rdn : Option[RDN]     = listRdns match {
    case r::_ =>
      //update RDN attribute content
      //start to delete old values, and then add new one (corner case with multivaluated attrs in rdn
      for(attr:String <- r.getAttributeNames.toSet) this deleteAttribute attr
      for(i <- 0 until r.getAttributeNames.size) {
        this.addValues(r.getAttributeNames()(i),r.getAttributeValues()(i))
      }
      Some(r)
    case _ => None
  }

  def dn : DN               = _backed.getParsedDN
  def rdn : Option[RDN]     = _rdn
  def parentDn : Option[DN] = _parentDn

  //////  Entry type  //////

  /**
   * Check if that entry has the given objectClass.
   * (Case insensitive).
   */
  def isA(objectClass:String) = _backed.hasObjectClass(objectClass)

  //////  Methods that deal with Attribute (presence, get attribute object, etc)  //////

  def hasAttribute(attributeName:String) = _backed.hasAttribute(attributeName)

  def hasAttribute(attribute:Attribute) = _backed.hasAttribute(attribute)

  def attributes : Iterable[Attribute]  = _backed.getAttributes.asScala

  def typedAttributes(implicit shema:Schema) : Iterable[TypedAttribute] = {
    _backed.getAttributes.asScala.map(a => TypedAttribute(a))
  }

  /**
   * Number of attribute in that entry
   */
  def attributesSize = _backed.getAttributes.size()

  /**
   * Return the attribute with the given name if
   * that entry has it, None else.
   */
  def attribute(attributeName:String) : Option[Attribute] = _backed.getAttribute(attributeName) match {
    case null => None
    case a => Some(a)
  }

  /**
   * Return the attribute with the given name.
   * Throws an exception if that attribute does not exists.
   */
  def getAttribute(attributeName:String) = _backed.getAttribute(attributeName)

  //////  Methods for direct access to value  //////

  /**
   * Check if the attribute with the given name has the
   * given value.
   * Return false if no such attribute exists.
   * Return true if the attribute is multivalued and at least one
   * value is the one given in argument.
   */
  def hasAttribueValue(attributeName:String,value:String) = _backed.hasAttributeValue(attributeName,value)

  /**
   * Return the set of values for the given attribute.
   * The set may be empty if the entry has not the given
   * attribute.
   */
  def valuesFor(attributeName:String) : Set[String] = _backed.getAttributeValues(attributeName) match {
    case null => Set()
    case x => x.toSet
  }

  /**
   * Return the Chunk of values for the given attribute.
   * The Chunk may be empty if the entry has not the given
   * attribute.
   * Using Chunk for performance, as constructing Set can be very costly
   */
  def valuesForChunk(attributeName:String) : Chunk[String] = _backed.getAttributeValues(attributeName) match {
    case null => Chunk.empty
    case x => Chunk.fromArray(x)
  }

  /**
   * Retrieve the first value of the given attribute if
   * attribute exists and is not null
   *
   * Most of the time, it's what you are looking for
   */
  def apply(attributeName:String) : Option[String] = _backed.getAttributeValue(attributeName) match {
    case null => None
    case s => Some(s)
  }

  /**
   * Get the first value for the given attribute name in an UNSAFE way.
   * @throws NoSuchElementException if the attribute is not present in entry
   */
  def value_!(attributeName:String) = apply(attributeName).getOrElse(throw new NoSuchElementException(attributeName))

  //////  Methods to retrieve the first value of attribute in typed way  //////

  def getAsBoolean(attributeName:String) : Option[Boolean] = _backed.getAttributeValueAsBoolean(attributeName) match {
      case null => None
    case b => Some(b.booleanValue)
  }
  def getAsDate(attributeName:String) : Option[java.util.Date] = _backed.getAttributeValueAsDate(attributeName) match {
      case null => None
      case x    => Some(x)
  }
   def getAsGTime(attributeName:String) : Option[GeneralizedTime] = apply(attributeName) flatMap { x =>
    GeneralizedTime.parse(x)
  }
  def getAsDn(attributeName:String) : Option[DN] = _backed.getAttributeValueAsDN(attributeName) match {
      case null => None
      case x    => Some(x)
  }
  def getAsInt(attributeName:String) : Option[Int] = _backed.getAttributeValueAsInteger(attributeName) match {
      case null => None
    case x => Some(x.intValue)
  }

  def getAsLong(attributeName:String) : Option[Long] = _backed.getAttributeValueAsLong(attributeName) match {
      case null => None
    case x => Some(x.longValue)
  }

  def getAsFloat(attributeName:String) : Option[Float] = _backed.getAttributeValue(attributeName) match {
    case null => None
    case x => try {
      Some(x.toFloat)
    } catch {
      case e:NumberFormatException => None
    }
  }

  //////  Method to update attribute  //////

  /**
   * set (replace existing one if needed) given attribute to given values
   * If only null or empty values are given, the
   * attribute with given name is removed.
   * In all case, null and empty values are removed.
   */
  def resetValuesTo(attributeName:String, values:String*) = {
    values match {
      case null =>
        _backed.removeAttribute(attributeName)
      case seq =>
        val toSave = seq.filter { s => null != s && s.nonEmpty }
        if(toSave.isEmpty) {
          _backed.removeAttribute(attributeName)
        } else {
         _backed.setAttribute(new Attribute(attributeName, toSave.asJava))
        }
    }
  }

  /** add given value(s) to given attribute. Create the attribute if does not exist */
  def mergeAttribute(attribute:Attribute) =  _backed.addAttribute(attribute)

  /** add given attribute. If already present, existing values will be merged */
  def addValues(attributeName:String, values:String*) =  _backed.addAttribute(new Attribute(attributeName, values.asJava))

  /** remove attribute */
  def deleteAttribute(attributeName:String) = _backed.removeAttribute(attributeName)

  //////  Standard methods (toString/equal etc) and LDIF method  //////

  override def toString() = _backed.toString
  def toLDIFString(sb:java.lang.StringBuilder) : Unit = _backed.toLDIFString(sb)
  def toLDIFString() : String = _backed.toLDIFString()
  def toLDIFRecord : LDIFRecord = _backed

  /*
   * A set which take an option, and remove the given attribute
   * when the argument is None.
   *
   * This method is especially used in domain mapping.
   */
  def  setOpt[A](a:Option[A], attributeName:String, f:A => String) : Unit = {
    a match {
      case None => this.deleteAttribute(attributeName)
      case Some(x) => this.resetValuesTo(attributeName, f(x))
    }
    () // unit is expected
  }

  override def equals(other:Any):Boolean = other match {
    case that:LDAPEntry => this._backed == that._backed
    case _ => false
  }

  override lazy val hashCode = 41 + _backed.hashCode

}

object LDAPEntry {
  /*
   * Create a new LDAPEntry from the given arguments.
   * The entry is copied and can be disposed after use.
   */
  val logger = LoggerFactory.getLogger(classOf[LDAPEntry])

  def apply(e:UnboundidEntry):LDAPEntry = new LDAPEntry(e.duplicate()) // val e = LDAPEntry(unboundidEntry)
  def apply(dn:DN, attributes:Attribute*):LDAPEntry = LDAPEntry(new UnboundidEntry(dn,attributes:_*))
  def apply(dn:DN, attributes:Iterable[Attribute]):LDAPEntry = LDAPEntry(new UnboundidEntry(dn, attributes.toSeq:_*))
  def apply(rdnAttribute:String,rdnValue:String,parentDn:String, attributes:Attribute*) : LDAPEntry = {
    require(rdnValue != null && rdnValue.nonEmpty)
    apply(new DN(s"${rdnAttribute}=${rdnValue},${parentDn}"),attributes)
  }

  def apply(rdn:Option[RDN],parentDn:Option[DN],attributes:Attribute*):LDAPEntry = {
    (rdn,parentDn) match {
      case (Some(r),Some(p)) => apply(new DN(r,p)      , attributes)
      case (Some(r), _     ) => apply(new DN(r::Nil:_*), attributes)
      case _                 => apply(NULL_DN          , attributes)
    }
  }

  /**
   *
   * - the merge is *always* resolved case sensitive
   *
   * - by default, it only update attributes in LDAP entry.
   *   That means that if entry in the directory has (a,b,c) attribute, and entry has only (a,b),
   *   then c won't be remove nor updated in LDAP
   * - attribute with no values are removed
   *   That means that if entry has attribute 'a' with no value, attribute 'a' will be removed in LDAP directory
   * - if "removeMissing" is set to true, then missing attribute in entry are marked to be removd (most of the time,
   *   it's not what you want).
   * - if "removeMissing" is set to true, you can still keep some attribute enumerated here. If removeMissing is false,
   *   that parameter is ignored.
   */
  def merge(
      sourceEntry               : LDAPEntry
    , targetEntry               : LDAPEntry
    , ignoreRDN                 : Boolean=true
    , removeMissing             : Boolean=false
    , forceKeepMissingAttributes: Seq[String] = Seq()
    , ignoreCaseOnAttributes    : Seq[String] = Seq("objectClass")
  ) : Buffer[Modification] = {

     // The exact String match rule to use on attributes that are not in ignoreCaseOnAttributes
    val rule = com.unboundid.ldap.matchingrules.CaseExactStringMatchingRule.getInstance

    // Set the matching Rule on target entry
    val target = LDAPEntry(targetEntry.backed.getParsedDN, targetEntry.backed.getAttributes.asScala.map { a =>
      if(ignoreCaseOnAttributes.contains(a.getName)) a
      else new Attribute(a.getName, rule, a.getValues:_*)
    })

    // Set matching Rule on source entry, as of unboundID 2.3.4, it is the one used to compare data
    val origin = LDAPEntry(sourceEntry.backed.getParsedDN, sourceEntry.backed.getAttributes.asScala.map { a =>
      if(ignoreCaseOnAttributes.contains(a.getName)) a
      else new Attribute(a.getName, rule, a.getValues:_*)
    })

    // Filter empty attributes
    val emptyAttrs = Buffer[String]()
    for(a <- target.attributes.toSeq) {
      if(!a.hasValue) {
        target deleteAttribute a.getName //remove the attribute
        emptyAttrs += a.getName
      }
    }

    val mods = if(removeMissing) { //compare all values
      //if forceKeepMissingAttributes, really compare all
      if(forceKeepMissingAttributes.isEmpty) {
        com.unboundid.ldap.sdk.Entry.diff(origin.backed,target.backed,true,false) //false: use replace in LDIF
      } else {
        //compare all attributes, safe empty attribute in targetEntry and in forceKeepMissingAttributes
        //( so that is attribute with value in e but not in targetEntry )
        val attrs = ( origin.attributes.map( _.getName ).toSet -- forceKeepMissingAttributes ) ++ targetEntry.attributes.toSeq.map( _.getName)
        com.unboundid.ldap.sdk.Entry.diff(origin.backed,target.backed,true,false, attrs.toSeq:_*) //false: use replace in LDIF
      }
    } else { //only compare attributes in e
      com.unboundid.ldap.sdk.Entry.diff(origin.backed,target.backed,true,false,targetEntry.attributes.toSeq.map( _.getName):_*)
    }
    mods
  }.asScala

  def diff(sourceEntry:LDAPEntry, targetEntry:LDAPEntry, onlyAttributes:Seq[String]) : Buffer[Modification] = {
    com.unboundid.ldap.sdk.Entry.diff(sourceEntry.backed,targetEntry.backed,true,false,onlyAttributes:_*).asScala
  }
}
