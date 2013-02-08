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

import com.unboundid.ldap.sdk.{DN,Attribute}
import com.unboundid.ldap.sdk.schema.Schema
import com.normation.exceptions.TechnicalException
import com.normation.utils.HashcodeCaching

/**
 * LDAP syntax 
 *
 */
sealed trait TypedAttribute {
  val name:String
}
case class BooleanAttribute(name:String,value:List[Boolean]) extends TypedAttribute with HashcodeCaching 
case class LongAttribute(name:String,values:List[Long]) extends TypedAttribute with HashcodeCaching 
case class DNAttribute(name:String,values: List[DN]) extends TypedAttribute with HashcodeCaching 
case class StringAttribute(name:String,values:List[String]) extends TypedAttribute with HashcodeCaching 
case class BinaryAttribute(name:String,values: List[Array[Byte]]) extends TypedAttribute with HashcodeCaching 
case class GeneralizedTimeAttribute(name:String,values:List[GeneralizedTime]) extends TypedAttribute with HashcodeCaching 


object TypedAttribute {
  
  private def toBoolean(s:String) : Boolean = s.toLowerCase match {
    case "true"  | "t" | "yes" | "y" | "on"  | "1" => true
    case "false" | "f" | "no"  | "n" | "off" | "0" => false
    case x => throw new TechnicalException("Can not interpret %s as a boolean value".format(x))
  }
  
  def apply(attribute:Attribute)(implicit schema:Schema) : TypedAttribute = {
    
    schema.getAttributeType(attribute.getName) match {
      case null => 
        StringAttribute(attribute.getName,attribute.getValues.toList)
      case attrDef => attrDef.getSyntaxOID(schema) match {
        case null => StringAttribute(attribute.getName,attribute.getValues.toList)
        case oid => schema.getAttributeSyntax(oid) match {
          case null =>
            StringAttribute(attribute.getName,attribute.getValues.toList)
          case syntaxDef => syntaxDef.getOID match {
            case "1.3.6.1.4.1.1466.115.121.1.7" => //boolean
              BooleanAttribute(attribute.getName,attribute.getValues.map(v => toBoolean(v)).toList)
            case "1.3.6.1.4.1.1466.115.121.1.41" => // Postal addr.
              StringAttribute(attribute.getName,attribute.getValues.toList)
            case "1.3.6.1.4.1.1466.115.121.1.12" |
                 "1.3.6.1.4.1.1466.115.121.1.34" => // name&optional UID
              DNAttribute(attribute.getName,attribute.getValues.map(v => new DN(v)).toList)
            case "1.3.6.1.4.1.1466.115.121.1.24" |
                 "1.3.6.1.4.1.1466.115.121.1.53" =>  // UTC time
              GeneralizedTimeAttribute(attribute.getName,attribute.getValues.map(v => GeneralizedTime(v)).toList)
            case "1.3.6.1.4.1.1466.115.121.1.27" => // Integer
              LongAttribute(attribute.getName,attribute.getValues.map(v => v.toLong).toList)
            case "1.3.6.1.4.1.1466.115.121.1.36" => // numeric 
              StringAttribute(attribute.getName,attribute.getValues.toList)
            case "1.3.6.1.4.1.4203.1.1.2" | // auth password
                 "1.3.6.1.4.1.1466.115.121.1.5" | // binary
                 "1.3.6.1.4.1.1466.115.121.1.8" | // certificate
                 "1.3.6.1.4.1.1466.115.121.1.9" | // cert list
                 "1.3.6.1.4.1.1466.115.121.1.10" | // cert pair
                 "1.3.6.1.4.1.1466.115.121.1.28" | // JPEG
                 "1.3.6.1.4.1.1466.115.121.1.40" => // octet string
             BinaryAttribute(attribute.getName,attribute.getRawValues.map(v => v.getValue).toList)
            case "1.3.6.1.4.1.1466.115.121.1.50" => //telephone number
             StringAttribute(attribute.getName,attribute.getValues.toList)
            case _ => //other are mapped as string
             StringAttribute(attribute.getName,attribute.getValues.toList)
          }
        }
      }
    }
  }
}
