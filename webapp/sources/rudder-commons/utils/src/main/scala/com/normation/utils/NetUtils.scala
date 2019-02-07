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

import sun.net.util.IPAddressUtil

/**
 *
 * Some methods to deals with IP and networks
 *
 */
object NetUtils {


  /**
   * Check if the given string is a network address,
   * i.e if it on the form IP(v4 or v6)/mask.
   * A single IP address will be accepted by the test.
   */
  def isValidNetwork(net:String) = {

    val parts = net.split("/")
    if(parts.size == 1) {
       IPAddressUtil.isIPv6LiteralAddress(parts(0)) ||
       IPAddressUtil.isIPv4LiteralAddress(parts(0))
    } else if(parts.size == 2) {
      (
       IPAddressUtil.isIPv6LiteralAddress(parts(0)) ||
       IPAddressUtil.isIPv4LiteralAddress(parts(0))
      ) && (
        try {
          val n = parts(1).toInt
          if(n >= 0 && n < 255) true else false
        } catch {
          case _:NumberFormatException => false
        }
      )
    } else false
  }

}