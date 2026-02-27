/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.plugins

import net.liftweb.common.Loggable
import net.liftweb.http.*
import scala.reflect.ClassTag
import scala.xml.NodeSeq

final case class SnippetExtensionKey(value: String)

/**
 * A default implement for extension point with only
 * one map contribution for before/after snippet dispatch.
 *
 * BE CAREFUL: a SnippetExtensionPoint IS NOT a snippet.
 * It's just a class with a pur method that will be used to compose
 * in a snippet.
 * Its instanciation is not managed by lift.
 * It does not have access to traditionnal lift magic available for snippets.
 *
 */
trait SnippetExtensionPoint[T] {
  val ttag: ClassTag[T]

  /**
   * The key for plugin to extends that snippet.
   */
  def extendsAt: SnippetExtensionKey = SnippetExtensionKey(ttag.runtimeClass.getSimpleName)

  /**
   * Here, we are rather strict on what we want : a T that is also extendable
   */
  def compose(snippet: T): Map[String, NodeSeq => NodeSeq]

}

/**
 * An extendable snippet that only does its extension if the current
 * status of the plugin is enabled
 */
trait PluginExtensionPoint[T] extends SnippetExtensionPoint[T] {

  def status: PluginStatus

  // only execute the transformation if the plugin is enable
  def guard(f: NodeSeq => NodeSeq)(xml: NodeSeq): NodeSeq = if (status.isEnabled()) f(xml) else xml

  // protect all compose method with the guard. The check is done each time to allow runtime
  // switch on plugin status
  final def compose(snippet: T): Map[String, NodeSeq => NodeSeq] = pluginCompose(snippet).view.mapValues(guard).toMap

  def pluginCompose(snippet: T): Map[String, NodeSeq => NodeSeq]
}

/**
 * This trait allows to plugin to connect and enhance that snippet.
 *
 * How plugin compose ?
 *
 * For each render function, a new dispatch is build.
 * BeforeRender and AfterRender applied in order, so that
 * these plugins will lead to:
 *
 * P1 : before: { bfoo -> p1bfoo ; bar -> p1bbar }
 *      after : { foo -> p1afoo  ; bar -> p1abar }
 * P2 : before: { bbaz -> p2bfoo ; bar -> p2bbar }
 *      after : { bar  -> p2abar }
 *
 * And that snippet plugDispatch is defined like:
 *  beforeRender: P1 :: P2 :: Nil
 *  afterRender : P1 :: P2 :: Nil
 *  plugDispatch: { foo -> pf ; bar -> pb }
 *
 * The resulting dispatch will be:
 * {
 *   bfoo -> xml => (p1bfoo)(xml)
 *   afoo -> xml => (p1afoo)(xml)
 *   bbaz -> xml => (p2bfoo)(xml)
 *   foo -> xml => (p1afoo ʘ pf)(xml)
 *   bar -> xml => (p1bbar ʘ p2abar ʘ pb ʘ p1abar ʘ p2abar)(xml)
 * }
 *
 */
trait ExtendableSnippet[T] extends DispatchSnippet with Loggable {
  self: T =>

  // reminder:
  // type DispatchIt = PartialFunction[String, NodeSeq => NodeSeq]

  def extendsAt: SnippetExtensionKey = SnippetExtensionKey(self.getClass.getSimpleName)

  def mainDispatch: Map[String, NodeSeq => NodeSeq]

  def beforeSnippetExtensionSeq: Seq[SnippetExtensionPoint[T]]

  def afterSnippetExtensionSeq: Seq[SnippetExtensionPoint[T]]

  def dispatch: DispatchIt = {
    (ExtendableSnippet.chached[T](extendsAt, computeDispatch))(this)
  }

  private def computeDispatch(snippet: T): DispatchIt = {
    val all = (beforeSnippetExtensionSeq.map(_.compose(snippet)) :+
      mainDispatch) ++
      afterSnippetExtensionSeq.map(_.compose(snippet))

    all.foldLeft(Map.empty[String, NodeSeq => NodeSeq]) {
      case (previous, current) =>
        previous ++ (for {
          (key, transform) <- current.iterator
        } yield {
          if (previous.isDefinedAt(key)) (key, transform.compose(previous(key)))
          else (key, transform)
        }).toMap
    }
  }
}

/**
 * keep a cache of computed
 * dispatch function for each class
 */
object ExtendableSnippet {
  type DispatchIt = PartialFunction[String, NodeSeq => NodeSeq]

  private val cache = scala.collection.mutable.Map.empty[SnippetExtensionKey, ? => DispatchIt]

  def chached[T](forSnippet: SnippetExtensionKey, withDefault: => T => DispatchIt): T => DispatchIt = {
    if (true) withDefault
    else cache.getOrElseUpdate(forSnippet, withDefault).asInstanceOf[(T => DispatchIt)]
  }
}
