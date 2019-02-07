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

import com.unboundid.ldap.sdk.Filter

package object schema {
  import scala.language.implicitConversions

  //implicits conversion between our ObjectClass object and an LDAP filter
  //that require that this objectclass is present
  implicit def objectClass2Filter(oc:LDAPObjectClass) : Filter = BuildFilter.EQ("objectClass",oc.name)

  //implicits conversion between our ObjectClasses object and an LDAP filter
  //that requires that all objectClasses are present
  implicit def objectClasses2Filter(oc:LDAPObjectClasses) : Filter =
    BuildFilter.AND(oc.names.map(n => BuildFilter.EQ("objectClass",n)).toSeq:_*)

  implicit def setObjectClass2ObjectClasses(ocs:Set[LDAPObjectClass]) = new LDAPObjectClasses(ocs)

  implicit def string2objectClass(name:String)(implicit schema: LDAPSchema) : LDAPObjectClass = schema(name)

}
