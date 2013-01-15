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

package com.normation.utils

/**
 * This is an utility object that
 * provides useful simple methods in a static way
 * 
 * Most methods deals with null dereferencing and 
 * null/empty Strings management. 
 */
object Utils {
  /**
   * Compare two lists of string as if they were two
   * path in a tree (starting with the same root). 
   */
  def recTreeStringOrderingCompare(a:List[String], b:List[String]) : Int = {
    (a,b) match {
      case (Nil, Nil) => 0
      case (Nil, _) => -1
      case (_ , Nil) => 1
      case (h1 :: t1 , h2 :: t2) => //lexical order on heads, recurse for tails on same head
         if(h1 == h2) recTreeStringOrderingCompare(t1,t2)
         else String.CASE_INSENSITIVE_ORDER.compare(h1, h2)
    }
  }

  /**
   * Get the manifest of the given type
   * Use like: val manifest = manifest[MY_TYPE]
   */
  def manifestOf[T](implicit m: Manifest[T]): Manifest[T] = m
  
  /**
   * Change a function: f:A => Option[B] in a 
   * partial function A => B
   */
  def toPartial[A,B](f: A => Option[B]) = new PartialFunction[A,B] {
    override def isDefinedAt(x:A) = f(x).isDefined
    override def apply(x:A) = f(x).get
  }
  
  
  /**
   * Rule:
   * in entity comparison, null string and empty strings 
   * are the same thing.
   */
  def sameStrings(s1:String,s2:String) = {
    if(s1 == null)  s2 == null || s2 == ""
    else s1 == s2
  }
  
  /**
   * Empty test on string, return true is the String is null or "", 
   * false otherwise
   */
  def isEmpty(s:String) = if(null == s || "" == s) true else false
  def nonEmpty(s:String) = if(null != s && s.length > 0) true else false
  
  /**
   * A safe dereference method, that allows to chain property
   * access without testing each level for nullity.
   * Of course, use it wisely and each time, wonder if a safer
   * option with return value being Option is not the way to go
   * This operator is especially useful when you depends upon 
   * a third party library or you are dealing with DAO-like
   * processing
   */  
  def ?[A <: AnyRef](block: => A) : A =
    try { 
      block 
    } catch {
      case e: NullPointerException => e.getStackTrace()(2).getMethodName match {
          case "$qmark" | "$qmark$qmark" | "$qmark$qmark$bang" => null.asInstanceOf[A]
          case _ => throw e
        }
    }
  
  /**
   * Safe deference with mapping to Option. 
   * Return None if deference to null, Some(a) otherwise
   */
  def ??[A <: AnyRef](block: => A): Option[A] = ?[A](block) match {
    case null => None
    case a =>  Some(a)
  }
  
  /**
   * A special case of safe dereferencing for string
   * that check that is string is not empty too
   */
  def ??!(block: => String):Option[String] = {
    ??[String](block) match {
      case Some(s) if(nonEmpty(s)) => Some(s)
      case _ => None
    }
  }
}