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

package com.normation.plugins

import scala.xml.NodeSeq

/**
 * 
 */

/**
 * A service that allow to find plugins by names or 
 */
trait SnippetExtensionRegister {

  /**
   * Retrieve the list of classes whose dispatch method should
   * be composed before the main class rendering
   */
  def getBeforeRenderExtension[T](plugAt:SnippetExtensionKey) : Seq[SnippetExtensionPoint[T]]
  
  /**
   * Retrieve the list of classes whose dispatch method should
   * be composed after the main class rendering
   */
  def getAfterRenderExtension[T](plugAt:SnippetExtensionKey) : Seq[SnippetExtensionPoint[T]]
  
  def register[T <: ExtendableSnippet[_]](extension: SnippetExtensionPoint[T]) : Unit

}

/**
 * Defatult implementation of the plugin register.
 * 
 * Extensions can be added at the class instanciation or
 * after, thanks to the "register" method.
 * In the future, extension should be orderable and overridable. 
 */
class SnippetExtensionRegisterImpl extends SnippetExtensionRegister {
  
  private[this] val extendsAfter  = scala.collection.mutable.Map.empty[SnippetExtensionKey, Seq[SnippetExtensionPoint[_]]]
  
  /**
   * register the given extension. The extension is added
   * as the last extension to be applied (and so at
   * the head of the sequence of extensions)
   */
  def register[T <: ExtendableSnippet[_]](extension: SnippetExtensionPoint[T]) : Unit = {
    println("resgistering: " + extension.extendsAt)
    extendsAfter.get(extension.extendsAt) match {
      case None => extendsAfter += (extension.extendsAt -> Seq(extension))
      case Some(exts) => extendsAfter += (extension.extendsAt ->  (extension +: exts) )
    }
  }
  
  private[this] val extendsBefore  = scala.collection.mutable.Map.empty[SnippetExtensionKey, Seq[SnippetExtensionPoint[_]]]
  
  /**
   * register the given extension. The extension is added
   * as the last extension to be applied (and so at
   * the head of the sequence of extensions)
   */
  def registerBefore(extension: SnippetExtensionPoint[ExtendableSnippet[_]]) : Unit = {
    println("resgistering: " + extension.extendsAt)
    extendsBefore.get(extension.extendsAt) match {
      case None => extendsBefore += (extension.extendsAt -> Seq(extension))
      case Some(exts) => extendsBefore += (extension.extendsAt ->  (extension +: exts) )
    }
  }  
  /**
   * Retrieve the list of classes whose dispatch method should
   * be composed before the main class rendering
   */
  def getBeforeRenderExtension[T](extendsAt:SnippetExtensionKey) : Seq[SnippetExtensionPoint[T]] = {
    extendsBefore.getOrElse(extendsAt, Seq()).map( x => x.asInstanceOf[SnippetExtensionPoint[T]])
  }
  
  /**
   * Retrieve the list of classes whose dispatch method should
   * be composed after the main class rendering
   */
  def getAfterRenderExtension[T](extendsAt:SnippetExtensionKey) : Seq[SnippetExtensionPoint[T]] = {
    extendsAfter.getOrElse(extendsAt, Seq()).map( x => x.asInstanceOf[SnippetExtensionPoint[T]])
  }

  
  
}