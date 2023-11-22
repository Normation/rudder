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

package com.normation.ldap.sdk.schema

final case class LDAPObjectClass(
    name: String,
    sup:  LDAPObjectClass = LDAPObjectClass.TOP,
    must: Set[String] = Set(),
    may:  Set[String] = Set()
) {

  val mustAttr: Set[String] = must ++ { if (null == sup) Set.empty else sup.mustAttr }
  val mayAttr:  Set[String] = may ++ { if (null == sup) Set.empty else sup.mayAttr }

  val attributes = mustAttr ++ mayAttr
  assert(null != name && name.nonEmpty, "Name can't be null or empty")
  assert(attributes.forall(a => null != a && a.nonEmpty), "Attributes name can't be null or empty")
}

object LDAPObjectClass {
  val TOP = new LDAPObjectClass("top", null, Set("objectClass"))
}

final case class LDAPObjectClasses(all: Set[LDAPObjectClass]) {
  assert(!all.isEmpty, "Object classes can't be empty (it should at least contains top)")

  val names:      Set[String] = all.map(_.name)
  val attributes: Set[String] = for {
    oc <- all
    x  <- oc.attributes
  } yield x
  val may:        Set[String] = for {
    oc <- all
    x  <- oc.mayAttr
  } yield x
  val must:       Set[String] = for {
    oc <- all
    x  <- oc.mustAttr
  } yield x
}

object LDAPObjectClasses {
  def apply(classes: LDAPObjectClass*) = new LDAPObjectClasses(Set() ++ classes)
}
