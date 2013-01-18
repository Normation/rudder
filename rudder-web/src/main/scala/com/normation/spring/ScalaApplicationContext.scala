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

package com.normation.spring

import org.springframework.web.context.WebApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.beans.factory.BeanDefinitionStoreException
import org.springframework.beans.factory.NoSuchBeanDefinitionException


/**
 * An utility classes that gives access to the
 * Spring bean registry in a more scala-ish way. 
 * 
 * See https://www.assembla.com/wiki/show/liftweb/Dependency_Injection
 * to see if it could be better integrated with idiomatic Lift
 * 
 */
trait ScalaApplicationContext[C <: ApplicationContext]{
  
  def springContext:C

  /**
   * A cache for the bean name, for Spring doesn't seem able to access them properly
   */
  val cache = scala.collection.mutable.Map[String, String]()
  /**
   * Inject the service only based on its type.
   * It is the default operation, but only work for services for which 
   * only one concrete implementation exists in the Spring bean registry.
   */
  def inject[T](implicit m: Manifest[T]) : T = {
    val beanId = cache.getOrElseUpdate(m.runtimeClass.getSimpleName(),
      springContext.getBeanNamesForType(m.runtimeClass.asInstanceOf[Class[T]]) match {
        case list if list.size == 1 => list(0)
        case list if list.size > 1 => throw new BeanDefinitionStoreException("Multiple beans match this type %s".format(m.runtimeClass.asInstanceOf[Class[T]]))
        case list if list.size == 0 => throw new NoSuchBeanDefinitionException("No beans match this type %s".format(m.runtimeClass.asInstanceOf[Class[T]]))
      })
    inject(beanId)(m)
  }

  /**
   * Inject the service based on its type and id.
   * Should be avoided when possible, as strings are
   * brittle when refactoring. 
   */
  def inject[T](id:String)(implicit m: Manifest[T]) : T = springContext.getBean(id, m.runtimeClass.asInstanceOf[Class[T]])

}


trait ScalaAnnotationConfigApplicationContext extends ScalaApplicationContext[AnnotationConfigApplicationContext] {
  private var _springContext:Option[AnnotationConfigApplicationContext] = None
  
  override def springContext = _springContext.getOrElse(sys.error("No application context has been defined yet"))
  
  def setToNewContext(basePackages: String*) : Unit = {
    _springContext = Some(new AnnotationConfigApplicationContext(basePackages:_*))
  }
  
  def setToNewContext(annotatedClasses:Class[_]) : Unit = {
    _springContext = Some(new AnnotationConfigApplicationContext(annotatedClasses))
  }
}

trait ScalaWebApplicationContext extends ScalaApplicationContext[WebApplicationContext] {
 
  private var _springContext:Option[WebApplicationContext] = None
  
  override def springContext = _springContext.getOrElse(sys.error("No application context has been defined yet"))
  
  def setToNewContext(webContext:WebApplicationContext) : Unit = {
    webContext match {
      case null => sys.error("Error when getting the application context from the web context. Missing ContextLoaderListener.")
      case c => _springContext = Some(c)
    }
  }
}