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

package com.normation

package object authorization {
  
  /**
   * Implicit transformation from AuthorizationType 
   * (and tuple of AuthorizationType up to 7) into Rights
   */
  private[this] type AT = AuthorizationType
  implicit def authzType2ToRights( t:(AT,AT)) : Rights = new Rights(t._1, t._2)
  implicit def authzType3ToRights( t:(AT,AT,AT)) : Rights = new Rights(t._1, t._2, t._3)
  implicit def authzType4ToRights( t:(AT,AT,AT,AT)) : Rights = new Rights(t._1, t._2, t._3, t._4)
  implicit def authzType5ToRights( t:(AT,AT,AT,AT,AT)) : Rights = new Rights(t._1, t._2, t._3, t._4, t._5)
  implicit def authzType6ToRights( t:(AT,AT,AT,AT,AT,AT)) : Rights = new Rights(t._1, t._2, t._3, t._4, t._5, t._6)
  implicit def authzType7ToRights( t:(AT,AT,AT,AT,AT,AT,AT)) : Rights = new Rights(t._1, t._2, t._3, t._4, t._5, t._6, t._7)
  
}