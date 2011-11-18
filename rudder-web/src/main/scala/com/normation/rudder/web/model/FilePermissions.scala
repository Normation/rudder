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

package com.normation.rudder.web.model


/**
 * Description of Unix file permissions 
 * for "user", "group" and "other" (whitout setuid,
 * setgid and sticky bit).
 * 
 * The goal of these classes is to make their
 * use as near as possible as Unix chmod in 
 * Scala.
 * 
 * ## First, Perm objects: simple
 * immutable permission objects with a nice
 * toString and an octal representation:
 * % val p:Perm = w
 * % w.toString // "-w-"
 * % w.octal // 2
 * 
 * You can combine permission to obtain new permissions:
 * % w+r  // rw-
 * % rwx-w // r-w
 * 
 * ## Second, FilePerms objects: a group of
 * three mutable permissions.
 * 
 * You can create a new File permission object
 * from octal values:
 * % val perms = FilePerms(777) 
 * Or from Perm object:
 * % val perms = FilePerms(rw,rw,r)
 * 
 * Like Perm, they have nice string and octal
 * reprsentation:
 * % val perms = FilePerms(777) 
 * % perms.toString //rwxrwxrwx
 * % perms.octal // 777
 * 
 * Missing values are initialized to "no permission":
 * % val perms = FilePerms(77) //rwxrwx---
 * % val perms = FilePerms(rw) //rw-------
 * 
 * And of course, you can change permissions:
 * % val perms = FilePerms(77) //rwxrwx---
 * % perms.g-wx  // rwxr----
 * % perms.ugo+x // rwxr-x--x
 * % perms.a-wx  // r--r-----
 * 
 * The use of such objects with Java IO to set 
 * actual file permission is let as an exercise 
 * for the reader ;)
 */

import scala.collection.BitSet
import com.normation.utils.Utils.isEmpty
import Perm._
import com.normation.utils.HashcodeCaching

trait Perm {
  def bits:BitSet
  lazy val octal:Int = bitSetToInt(bits)

  def +(p:Perm) : Perm = Perm(p.bits | bits)
  def +(i:Byte) : Perm = Perm(i) match {
    case Some(p) => this+(p)
    case None => this
  }
  
  def -(p:Perm) : Perm = Perm(bits &~ p.bits) 
  def -(o:Byte) : Perm = Perm(o) match {
    case Some(p) => this-(p)
    case None => this
  }
  
  def read = bits(2)
  def write = bits(1)
  def exec = bits(0)
}

case object none extends Perm { 
  val bits = BitSet() 
  override def toString() = "---"
}
case object r extends Perm { 
  val bits = BitSet(2) 
  override def toString() = "r--"
}
case object w extends Perm { 
  val bits = BitSet(1)  
  override def toString() = "-w-"
}
case object x extends Perm { 
  val bits = BitSet(0)
  override def toString() = "--x"
}
case object rw extends Perm { 
  val bits = BitSet(2,1)  
  override def toString() = "rw-"
}
case object rx extends Perm { 
  val bits = BitSet(2,0)
  override def toString() = "r-x"
}
case object wx extends Perm { 
  val bits = BitSet(1,0)
  override def toString() = "-wx"
}
case object rwx extends Perm { 
  val bits = BitSet(2,1,0)  
  override def toString() = "rwx"
}

object Perm {
  //utility methods to see the bitset as an int
  //unknown result for bs.size > 31
  private def bitSetToInt(bs:BitSet) : Int = (0 /: bs){ (o,j) =>  o | 1 << j }
  
  def apply(o:Byte) = o match {
    case 0 => Some(none)
    case 1 => Some(x)
    case 2 => Some(w)
    case 3 => Some(wx)
    case 4 => Some(r)
    case 5 => Some(rx)
    case 6 => Some(rw)
    case 7 => Some(rwx)
    case _ => None
  }
  
  //match order: read / write / exec
  def apply(bits:BitSet) = (bits(2),bits(1),bits(0)) match {
    case (true,false,false) => r
    case (false,true,false) => w
    case (true,true,false) => rw
    case (false,false,true) => x
    case (true,false,true) => rx
    case (false,true,true) => wx
    case (true,true,true) => rwx
    case _ => none
  }
  
  def unapply(s:String) : Option[Perm] = {
    try {
      apply(s.toByte) 
    } catch {
      case e:NumberFormatException =>
        s.toLowerCase match {
          case "r" => Some(r)
          case "w" => Some(w)
          case "rw" => Some(rw)
          case "x" => Some(x)
          case "rx" => Some(rx)
          case "wx" => Some(wx)
          case "rwx" => Some(rwx)
          case "none" => Some(none)
          case _ => None
        }
      case e:Exception => None
    }
  } 
  
  def unapply(c:Char) : Option[Perm] = unapply(c.toString)
  
  def allPerms = Set(none,r,w,x,rw,rx,wx,rwx)
}

case class PermSet(file:FilePerms,perms:(Perm => Unit, () => Perm)* )  extends HashcodeCaching {
  
  /*
   * Add to all perms the given rights
   */
  def +(p:Perm) : FilePerms = { 
    perms foreach { case(set,get) =>
      set(get() + p)
    }
    file 
  }
  def +(o:Byte) : FilePerms = Perm(o) match {
    case Some(p) => this.+(p) 
    case None => file
  }
  
  /*
   * Remove to all perms the given rights
   */
  def -(p:Perm) : FilePerms = { 
    perms foreach { case(set,get) =>
      set(get() - p)
    }
    file
  }
  def -(o:Byte) : FilePerms = Perm(o) match {
    case Some(p) => this.-(p)
    case None => file
  }
  
  def octal:String = ("" /: perms ) { (s,p) => s + p._2().octal.toString }
  
  //simule erad/write/exec vars
  def read = (true /: perms){ (b,p) => b & p._2().read }
  def read_=(b:Boolean) { if(b) this+(r) else this-(r) }
  def write = (true /: perms){ (b,p) => b & p._2().write }
  def write_=(b:Boolean) { if(b) this+(w) else this-(w) }
  def exec = (true /: perms){ (b,p) => b & p._2().exec }
  def exec_=(b:Boolean) { if(b) this+(x) else this-(x) }
  
}

object PermSet {
  val u = (file:FilePerms) => new PermSet(file,(file._u_= _ , file._u _))
  val g = (file:FilePerms) => new PermSet(file,(file._g_= _ , file._g _))
  val o = (file:FilePerms) => new PermSet(file,(file._o_= _ , file._o _))
  val ug = (file:FilePerms) => new PermSet(file,(file._u_= _ , file._u _),(file._g_= _ , file._g _))
  val uo = (file:FilePerms) => new PermSet(file,(file._u_= _ , file._u _),(file._o_= _ , file._o _))
  val go = (file:FilePerms) => new PermSet(file,(file._g_= _ , file._g _),(file._o_= _ , file._o _))
  val ugo = (file:FilePerms) => new PermSet(file,(file._u_= _ , file._u _),(file._g_= _ , file._g _),(file._o_= _ , file._o _))
  val a = (file:FilePerms) => new PermSet(file,(file._u_= _ , file._u _),(file._g_= _ , file._g _),(file._o_= _ , file._o _))
}

class FilePerms( //special bits have to be explicitly set
  var _u:Perm=none,
  var _g:Perm=none,
  var _o:Perm=none
) {
  
  val u = PermSet.u(this)
  val g = PermSet.g(this)
  val o = PermSet.o(this)
  val ug = PermSet.ug(this)
  val uo = PermSet.uo(this)
  val go = PermSet.go(this)
  val ugo = PermSet.ugo(this)
  val a = PermSet.a(this)
  
  def octal:String = "%s%s%s".format(_u.octal.toString,_g.octal.toString,_o.octal.toString)
  
  override def toString = "%s%s%s".format(_u.toString,_g.toString,_o.toString)
  
  def set(that:FilePerms) = {
    this._u = that._u
    this._g = that._g
    this._o = that._o
  }

  def set(u:Perm = none, g:Perm = none, o : Perm = none) = {
    this._u = u
    this._g = g
    this._o = o
  }

  override def equals(other:Any) = other match {
    case that:FilePerms => this._u == that._u && this._g == that._g && this._o == that._o
    case _ => false
  }
  
  override def hashCode() = this._u.hashCode + this._g.hashCode * 7 + this._o.hashCode * 31
}

object FilePerms {
  def apply(perms:String) : Option[FilePerms] = { //only understand one to three char
    if(isEmpty(perms)) None
    else perms.toList match {
      case Perm(x) :: Nil => Some(FilePerms(x)) 
      case Perm(x) :: Perm(y) :: Nil  => Some(FilePerms(x,y))
      case Perm(x) :: Perm(y) :: Perm(z) :: Nil  => Some(FilePerms(x,y,z))
      case _ => None
    }
    
  }
  
  def apply(perms:Int) : Option[FilePerms] = apply(perms.toString)
  
  def apply(u:Perm=none,g:Perm=none,o:Perm=none) = new FilePerms(u,g,o)
  def unapply(perm:FilePerms) : Option[(Perm,Perm,Perm)] = {
    Some((perm._u,perm._g,perm._o))
  }
  def unapply(ugo:(String,String,String)) : Option[(Perm,Perm,Perm)] = {
    ugo match {
      case (Perm(x),Perm(y),Perm(z)) => Some(x,y,z)
      case _ => None
    }
  }
  
  def allPerms : Set[FilePerms] = for {
    u <- Perm.allPerms
    g <- Perm.allPerms
    o <- Perm.allPerms
  } yield FilePerms(u,g,o)

  
  /*
   * Used like: chmod( ugo(_)+rw, file)
   * [so bad for the (_)]
   */
  def chmod( perms:FilePerms => FilePerms, file:FilePerms ) = perms(file)
}
