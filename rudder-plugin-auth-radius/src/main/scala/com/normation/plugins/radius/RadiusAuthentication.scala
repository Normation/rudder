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

package com.normation.plugins.radius

import java.net.InetAddress
import java.net.UnknownHostException

import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

import org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException

import net.jradius.client.RadiusClient
import net.jradius.client.auth.PAPAuthenticator
import net.jradius.client.auth.RadiusAuthenticator
import net.jradius.dictionary.Attr_ReplyMessage
import net.jradius.dictionary.Attr_State
import net.jradius.dictionary.Attr_UserName
import net.jradius.dictionary.Attr_UserPassword
import net.jradius.exception.RadiusException
import net.jradius.packet.AccessAccept
import net.jradius.packet.AccessChallenge
import net.jradius.packet.AccessReject
import net.jradius.packet.AccessRequest
import net.jradius.packet.attribute.AttributeFactory
import net.jradius.packet.attribute.AttributeList


/**
 * A security context that can hold security challange response
 * from radius.
 *
 * This is really springy, and mutable. Can't do otherwise.
 */
final class RadiusSecurityContext extends SecurityContextImpl {

  var isChallengeResponseBased: Boolean = false
  var stateAttribute: Array[Byte] = Array()

  override def equals(o: Any) = o match {
    case that: RadiusSecurityContext =>
      if(this eq that) true
      else {(
           super.equals(that)
        && this.isChallengeResponseBased == that.isChallengeResponseBased
        && this.stateAttribute == that.stateAttribute
      )}
    case _ => false
  }

  override def hashCode = {
    37 * super.hashCode() +
    stateAttribute.hashCode() * 17 +
    (if(isChallengeResponseBased) 1 else 0)
  }
}


/**
 * An authentication provider for Radius, only in charge of the authentication part.
 * The user details are provided by an external user services.
 *
 * @parameter radiusHost IP or hostname of the radius host
 * @parameter radiusAuthPort the port to use to connect to the radius server
 * @parameter sharedSecret the shared secret used to authenticate that application to radius server
 * @parameter radiusAuthProtocol the protocol to use for authentication. default is PAP. You can
 *            append key=value parameters, separated by ":" to the protocol name to specify
 *            protocol option.
 *            Authorized protocol names are: pap, chap, eap-md5, eap-ttls.
 *            ex for eap-ttls: "eap-tls:keyFile=keystore:keyPassword=mypass"
 * @parameter timeout timout in second to radius server, default 30s
 * @parameter retries connection retries before giving up, default 0
 *
 */
class RadiusAuthenticationProvider(
    userDetailsService: UserDetailsService
    //connection to radius context
  , radiusHost        : String //IP or hostname of the radius host
  , radiusAuthPort    : Int = 1812
  , radiusAuthProtocol: String
  , sharedSecret      : String // radius shared secret
  , timeout           : Int = 45   // socket timeout to radius server
  , retries           : Int = 0
) extends AbstractUserDetailsAuthenticationProvider {

  AttributeFactory.loadAttributeDictionary("net.jradius.dictionary.AttributeDictionaryImpl")

  private[this] val radiusClient: RadiusClient = {
    val radiusAddress = try {
      InetAddress.getByName(radiusHost)
    } catch {
      case ex: UnknownHostException =>
        throw new AuthenticationServiceException(s"Unknown radius host '${radiusHost}'", ex)
    }

    val radiusClient = new RadiusClient(radiusAddress, sharedSecret)
    radiusClient.setAuthPort(radiusAuthPort)
    radiusClient.setSocketTimeout(timeout)

    radiusClient
  }

  private[this] val authenticator: RadiusAuthenticator = {
    RadiusClient.getAuthProtocol(radiusAuthProtocol) match {
      case null => new PAPAuthenticator()
      case x => x
    }
    }

  private[this] def buildRequest(username: String, password: String): AccessRequest = {
      val attrs = new AttributeList()
      //erk.. So long immutability
      attrs.add(new Attr_UserName(username))
      attrs.add(new Attr_UserPassword(password))
      new AccessRequest(radiusClient, attrs)
  }

  protected def additionalAuthenticationChecks(userDetails: UserDetails, authenticationToken: UsernamePasswordAuthenticationToken): Unit = {}

  protected def retrieveUser(username: String, authenticationToken: UsernamePasswordAuthenticationToken): UserDetails = {

    /*
     * spring think that  username can be null/empty and authentication token to
     */
    if(
         null == username
      || username.isEmpty()
      || null == authenticationToken.getCredentials
    ) {
      throw new BadCredentialsException("Username and password are required to proceed to a radius authentication")
    }

    val password = authenticationToken.getCredentials match {
      case x: String if(x.size > 0) => x
      case x => throw new BadCredentialsException("Unknow credential token type for radius authentication (only non empty text passwords are supported): " + x)
    }

    val request = buildRequest(username, password)
    //if the securityContext is not a radius one, that means that we are not yet on challenge.
    //if it is, check its content to know.
    //modify request accordingly
    SecurityContextHolder.getContext() match {
      case context: RadiusSecurityContext =>
        if(context.isChallengeResponseBased) {
          // Challenge-Response scenario means we must include the state
          // attribute in the next request
          request.addAttribute(new Attr_State(context.stateAttribute))
        }

      case _ => //nothing to do here
    }

    //now, authentication
    val reply = try {
      // send and receive RADIUS packets to the specified address and port
      // with 2 retries (in case of no response)
      radiusClient.authenticate(request, authenticator, retries)
    } catch {
      case ex: RadiusException =>
        throw new AuthenticationServiceException("An error occured when trying to process radius authentication", ex)
    }


    //analyse reply

    reply match {
      case null => //we timedout
        throw new AuthenticationServiceException(s"Authentication to radius server timeout after ${timeout}s: ${radiusHost}:${radiusAuthPort}")

      case accept: AccessAccept =>
        //authentication success

        //reset authentication context
        val sc = new SecurityContextImpl()
        sc.setAuthentication(SecurityContextHolder.getContext.getAuthentication)
        SecurityContextHolder.setContext(sc)

        //find the user in user details service and returned it
        try {
          userDetailsService.loadUserByUsername(username)
        } catch {
          case ex: UsernameNotFoundException =>
            throw new BadCredentialsException(s"Radius authentication of user '${username}' to server ${radiusHost}:${radiusAuthPort} succeeded but the user was not found in the list of Rudder user. Please check the rudder-user.xml file content.")
        }

      case reject: AccessReject =>
        throw new BadCredentialsException(s"Radius authentication of user '${username}' to server ${radiusHost}:${radiusAuthPort} rejected")

      case challenge: AccessChallenge =>
        //not sure about that part, taken from internet, what could go wrong.

        // store the state attribute in the context (to be used in subsequent request(s))
        val replyStateAttribute = reply.getAttributeValue(Attr_State.TYPE).asInstanceOf[Array[Byte]]

        val sc = new RadiusSecurityContext()
        sc.setAuthentication(SecurityContextHolder.getContext.getAuthentication)
        sc.stateAttribute = replyStateAttribute
        sc.isChallengeResponseBased = true
        SecurityContextHolder.setContext(sc)

        //get reply message, strip null characters
        val replyMessage = challenge.getAttributeValue(Attr_ReplyMessage.TYPE).asInstanceOf[String].replaceAll("\u0000", "");
        throw new BadCredentialsException("The server has issued a challenge and is waiting for" +
          " a reply. Please follow the instructions and enter a response in the <b>SecurID Passcode</b> field (Username is disabled" +
          " temporarily). " + replyMessage)
    }

  }

}
