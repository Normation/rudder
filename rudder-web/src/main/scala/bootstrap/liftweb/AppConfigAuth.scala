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

package bootstrap.liftweb

import java.io.File
import scala.collection.JavaConversions.seqAsJavaList
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportResource
import org.springframework.core.io.{ ClassPathResource => CPResource }
import org.springframework.core.io.{ FileSystemResource => FSResource }
import org.springframework.core.io.Resource
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.authentication.encoding.Md5PasswordEncoder
import org.springframework.security.authentication.encoding.PasswordEncoder
import org.springframework.security.authentication.encoding.PlaintextPasswordEncoder
import org.springframework.security.authentication.encoding.ShaPasswordEncoder
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.AuthenticationEntryPoint
import com.normation.authorization.Create
import com.normation.authorization.Delete
import com.normation.authorization.Read
import com.normation.authorization.Rights
import com.normation.authorization.Search
import com.normation.authorization.Write
import com.normation.rudder.api.ApiToken
import com.normation.rudder.api.RoApiAccountRepository
import com.normation.rudder.authorization.AuthzToRights
import com.normation.rudder.authorization.NoRights
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.utils.HashcodeCaching
import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.common.Failure

import org.springframework.security.ldap.userdetails.UserDetailsContextMapper
import org.springframework.ldap.core.DirContextAdapter
import org.springframework.ldap.core.DirContextOperations
import java.util.Collection

/**
 * Spring configuration for user authentication.
 *
 * We are looking for an XML file with looking like:
 *
 * <authentication hash="sha">
 *  <user name="name1" password="p1" />
 *  <user name="name2" password="p2" />
 * </authentication>
 *
 */
@Configuration
@ImportResource(Array("classpath:applicationContext-security.xml"))
class AppConfigAuth extends Loggable {
  import AppConfigAuth._

  ///////////// FOR WEB INTERFACE /////////////


  @Bean def demoAuthenticationProvider : AuthenticationProvider = {

    val resource = getUserResourceFile
    //try to read and parse the file for users
    parseUsers(resource) match {
      case Some(config) =>
        val userDetails = new RudderInMemoryUserDetailsService(config.users.map { case (login,pass,roles) =>
          RudderUserDetail(login,pass,roles)
        }.toSet)

        val provider = new DaoAuthenticationProvider()
        provider.setUserDetailsService(userDetails)
        provider.setPasswordEncoder(config.encoder)
        provider
      case None =>
        ApplicationLogger.error("Error when trying to parse user file '%s', aborting.".format(resource.getURL.toString))
        throw new javax.servlet.UnavailableException("Error when triyng to parse user file '%s', aborting.".format(resource.getURL.toString))
    }
  }

  ///////////// FOR REST API /////////////

  @Bean def restAuthenticationFilter = new RestAuthenticationFilter(RudderConfig.roApiAccountRepository)

  @Bean def restAuthenticationEntryPoint = new AuthenticationEntryPoint() {
    override def commence(request: HttpServletRequest, response: HttpServletResponse, ex: AuthenticationException) : Unit = {
        response.sendError( HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized" )
    }
  }

  /**
   * Map an user from XML user config file
   */
  @Bean def rudderXMLUserDetails : UserDetailsContextMapper = {
    val resource = getUserResourceFile
    parseUsers(resource) match {
      case Some(config) =>
        new RudderXmlUserDetailsContextMapper(config)
      case None =>
        ApplicationLogger.error("Error when trying to parse user file '%s', aborting.".format(resource.getURL.toString))
        throw new javax.servlet.UnavailableException("Error when triyng to parse user file '%s', aborting.".format(resource.getURL.toString))
    }
  }
}

/**
 *  A trivial, immutable implementation of UserDetailsService for RudderUser
 */
class RudderInMemoryUserDetailsService(private[this] val initialUsers:Set[RudderUserDetail]) extends UserDetailsService {
  private[this] val users = Map[String,RudderUserDetail](initialUsers.map(u => (u.login,u)).toSeq:_*)

  @throws(classOf[UsernameNotFoundException])
  override def loadUserByUsername(username:String) : RudderUserDetail = {
    users.getOrElse(username, throw new UsernameNotFoundException(s"User with username '%{username}' was not found"))
  }
}


/**
 * For now, we don't use at all Spring Authority to implements
 * our authorizations.
 * That because we want something more typed than String for
 * authority, and as a bonus, that allows to be able to switch
 * from Spring more easily
 *
 * So we have only one Authority type known by Spring Security: ROLE_USER
 */
case object RoleUserAuthority extends GrantedAuthority {
  override val getAuthority = "ROLE_USER"
}

case object RoleApiAuthority extends GrantedAuthority {
  override val getAuthority = "ROLE_REMOTE"

  val apiRudderRights = new Rights(Read, Write, Create, Delete, Search)
}

/**
 * Our simple model for for user authentication and authorizations.
 * Note that authorizations are not managed by spring, but by the
 * 'authz' token of RudderUserDetail.
 */
case class RudderUserDetail(login:String,password:String,authz:Rights, grantedAuthorities: Seq[GrantedAuthority] = Seq(RoleUserAuthority)) extends UserDetails with HashcodeCaching {
  override val getAuthorities:java.util.Collection[GrantedAuthority] = grantedAuthorities
  override val getPassword = password
  override val getUsername = login
  override val isAccountNonExpired = true
  override val isAccountNonLocked = true
  override val isCredentialsNonExpired = true
  override val isEnabled = true
}

/**
 * Our rest filter, we just look for X-API-Token and
 * ckeck if a token exists with that value
 */
class RestAuthenticationFilter(
    apiTokenRepository: RoApiAccountRepository
  , headerName: String = "X-API-Token"
) extends Filter with Loggable {
  def destroy(): Unit = {}
  def init(config: FilterConfig): Unit = {}


  private[this] val REST_USER_PREFIX = "REST Account: "

  private[this] val api_v1_url = List(
      "/api/status"
    , "/api/techniqueLibrary/reload"
    , "/api/dyngroup/reload"
    , "/api/deploy/reload"
    , "/api/archives"
  )

  private[this] def isValidNonAuthApiV1(httpRequest:HttpServletRequest) : Boolean = (
       RudderConfig.RUDDER_REST_ALLOWNONAUTHENTICATEDUSER
    && {
         val requestPath = httpRequest.getRequestURI.substring(httpRequest.getContextPath.length)
         api_v1_url.exists(path => requestPath.startsWith(path))
       }
  )

  private[this] def failsAuthentication(httpResponse: HttpServletResponse, eb: EmptyBox) : Unit = {
    val e = eb ?~! "REST authentication failed"
    logger.debug(e.messageChain)
    e.rootExceptionCause.foreach( ex => logger.debug(ex))
    httpResponse.sendError( HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized" )
  }

  private[this] def authenticate(userDetails: RudderUserDetail) : Unit = {
    val authenticationToken = new UsernamePasswordAuthenticationToken(
        userDetails
      , userDetails.getAuthorities
      , userDetails.getAuthorities
    )

    //save in spring security context
    SecurityContextHolder.getContext().setAuthentication(authenticationToken)
  }

  /**
   * Look for the X-API-TOKEN header, and use it
   * to try to find a correspond token,
   * and authenticate in SpringSecurity with that.
   */
  def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {

    (request, response) match {

      case (httpRequest:HttpServletRequest, httpResponse:HttpServletResponse) =>

        httpRequest.getHeader(headerName) match {

          case null | "" =>

            /* support of API v1 rest.AllowNonAuthenticatedUser */
            if(isValidNonAuthApiV1(httpRequest)) {
              authenticate(RudderUserDetail(
                  "UnknownRestUser"
                 , ""
                 , RoleApiAuthority.apiRudderRights
                 , Seq(RoleApiAuthority)
              ))
              chain.doFilter(request, response)
            } else {
              failsAuthentication(httpResponse, Failure(s"Missing or empty HTTP header ${headerName}"))
            }

          case token =>
            //try to authenticate

            apiTokenRepository.getByToken(ApiToken(token)) match {
              case eb:EmptyBox =>
                failsAuthentication(httpResponse, eb)

              case Full(None) =>
                failsAuthentication(httpResponse, Failure(s"No registered token '${token}'"))

              case Full(Some(principal)) =>
                if(principal.isEnabled) {
                  val rest_principal = REST_USER_PREFIX + s""""${principal.name.value}"""" + s" (${principal.id.value.toUpperCase()})"
                  //cool, build an authentication token from it
                  authenticate(RudderUserDetail(
                      rest_principal
                    , principal.token.value
                    , RoleApiAuthority.apiRudderRights
                    , Seq(RoleApiAuthority)
                  ))

                  chain.doFilter(request, response)

                } else {
                  failsAuthentication(httpResponse, Failure(s"Account with ID ${principal.id.value} is disabled"))
                }
            }
        }



      case _ =>
        //can not do anything with that, chain filter.
        chain.doFilter(request, response)
    }


  }
}


case class AuthConfig(
  encoder: PasswordEncoder,
  users:List[(String,String,Rights)]
) extends HashcodeCaching

class RudderXmlUserDetailsContextMapper(authConfig: AuthConfig) extends UserDetailsContextMapper {

  val users = authConfig.users.map { case(login,pass,roles) => (login, RudderUserDetail(login,pass,roles)) }.toMap

  //we are not able to try to save user in the XML file
  def mapUserToContext(user: UserDetails, ctx: DirContextAdapter) : Unit = ()

  def mapUserFromContext(ctx: DirContextOperations, username: String, authorities: Collection[_ <:GrantedAuthority]): UserDetails = {
    users.getOrElse(username, RudderUserDetail(username, "", new Rights(NoRights)))
  }

}

object AppConfigAuth extends Loggable {

  val JVM_AUTH_FILE_KEY = "rudder.authFile"
  val DEFAULT_AUTH_FILE_NAME = "demo-rudder-users.xml"

  def getUserResourceFile() : Resource =  System.getProperty(JVM_AUTH_FILE_KEY) match {
      case null | "" => //use default location in classpath
        ApplicationLogger.info("JVM property -D%s is not defined, use configuration file '%s' in classpath".format(JVM_AUTH_FILE_KEY, DEFAULT_AUTH_FILE_NAME))
        new CPResource(DEFAULT_AUTH_FILE_NAME)
      case x => //so, it should be a full path, check it
        val config = new FSResource(new File(x))
        if(config.exists && config.isReadable) {
          ApplicationLogger.info("Use configuration file defined by JVM property -D%s : %s".format(JVM_AUTH_FILE_KEY, config.getPath))
          config
        } else {
          ApplicationLogger.error("Can not find configuration file specified by JVM property %s: %s ; abort".format(JVM_AUTH_FILE_KEY, config.getPath))
          throw new javax.servlet.UnavailableException("Configuration file not found: %s".format(config.getPath))
        }
    }

  def parseUsers(resource:Resource) : Option[AuthConfig] = {
    if(resource.exists && resource.isReadable) {
      val xml = scala.xml.XML.load(resource.getInputStream)
      //what password hashing algo to use ?
      val root = (xml \\ "authentication")
      if(root.size != 1) {
        val msg = "Authentication file is malformed, the root tag '<authentication>' was not found"
        ApplicationLogger.error(msg)
        None
      } else {
        val hash = (root(0) \ "@hash").text.toLowerCase match {
          case "sha" | "sha1" => new ShaPasswordEncoder(1)
          case "sha256" | "sha-256" => new ShaPasswordEncoder(256)
          case "sha512" | "sha-512" => new ShaPasswordEncoder(512)
          case "md5" => new Md5PasswordEncoder
          case _ => new PlaintextPasswordEncoder
        }

        //now, get users
        val users = ( (xml \ "user").toList.flatMap { node =>
         //for each node, check attribute name (mandatory), password  (mandatory) and role (optional)
         (   node.attribute("name").map(_.toList.map(_.text))
           , node.attribute("password").map(_.toList.map(_.text))
           , node.attribute("role").map(_.toList.map( role => AuthzToRights.parseRole(role.text.split(",").toSeq.map(_.trim))))
         ) match {
           case (Some(name :: Nil) , Some(pwd :: Nil), roles ) if(name.size > 0 && pwd.size > 0) => roles match {
             case Some(roles:: Nil) => (name, pwd, roles) :: Nil
             case _ =>  (name, pwd, new Rights(NoRights)) :: Nil
           }

           case _ =>
             ApplicationLogger.error("Ignore user line in authentication file '%s', some required attribute is missing: %s".format(resource.getURL.toString, node.toString))
             Nil
         }
        })

        //and now, return the list of users
        users map { user =>
        if (user._3.authorizationTypes.contains(NoRights))
          ApplicationLogger.warn("User %s authorisation are not defined correctly, please fix it (defined authorizations: %s)".format(user._1,user._3.authorizationTypes.map(_.id.toLowerCase()).mkString(", ")))
        ApplicationLogger.debug("User %s with defined authorizations: %s".format(user._1,user._3.authorizationTypes.map(_.id.toLowerCase()).mkString(", ")))
        }
        Some(AuthConfig(hash, users))
      }
    } else {
      ApplicationLogger.error("The resource '%s' does not exist or is not readable".format(resource.getURL.toString))
      None
    }
  }
}

