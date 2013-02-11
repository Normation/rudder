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

package net.liftweb.http

import net.liftweb.common._
import scala.reflect.ClassTag

/**
 * That class allow to load and register a snippet for the context of
 * a Lift request.
 *
 *
 * Such snippet should not be reachable directly from the application,
 * and so should not be placed in the "snippet" package.
 *
 * We could call these local snippets "components".
 *
 * This is useful to split a big page into sub part, especially when some of
 * these part are (complex) forms based on some user action on other part of
 * the page (and so, forms are contextualized).
 *
 *
 * How to use it:
 *
 * <pre>
 * MyPage.scala
 *
 * class MyPage extends DispatchSnippet {
 *
 *   val myComponentHolder : LocalSnippet[MyComponent] = new LocalSnippet[MyComponent]
 *
 *   def dispatch = {
 *     case "myComponent" => displayComponent _
 *   }
 *
 *   def displayComponent(xml:NodeSeq) : NodeSeq = {
 *     myComponentHolder.is match {
 *       case Failure(m,_,_) =>
 *           //error with the component loading or update, display an error message
 *           //here, <lift:MyComponent.renderComponent /> will lead to Lift saying it does not know that class
 *           <span class="error">Error: {m}</span>
 *       case Empty =>
 *           //the component was not initialize for that request, show an error message or a way to initialize it (by ajax)
 *           //here too, <lift:MyComponent.renderComponent /> will lead to Lift saying it does not know that class
 *           <span><a href="#" ...>Please click here to init the component</a></span>
 *       case Full(myComponent) =>
 *           //render component, as it is now registered
 *           <lift:MyComponent.renderComponent />
 *     }
 *   }
 *
 *   //to register the component, you just have to set a value to LocalSnippet
 *   def initComponent : Unit = {
 *     myComponentHolder.set(  new MyComponent(context, param, for, that, component)  )
 *   }
 * }
 *
 * MyComponent.scala
 *
 * class MyComponent(some:Context, param:For, that:Componenent) extends DispatchSnippet {
 *
 *   def dispatch = {
 *     case "renderComponent" => renderComponent _
 *   }
 *
 *   def renderComponent(xml:NodeSeq) : NodeSeq = { // render component }
 * }
 *
 * </pre>
 *
 */
class LocalSnippet[T <: DispatchSnippet](implicit m: ClassTag[T]) {
  private[this] var _snippet : Box[T]= Empty

  val name = m.runtimeClass.getSimpleName

  def is = _snippet

  def set(snippet:Box[T]) : Unit = {
    _snippet = snippet
    snippet match {
      case _:EmptyBox => S.unsetSnippetForClass(name)
      case Full(s) => S.overrideSnippetForClass(name, s)
    }
  }
}