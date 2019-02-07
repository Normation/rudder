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

package bootstrap.liftweb

import java.io.File
import java.util.Collection

import com.github.ghik.silencer.silent
import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.Role
import com.normation.rudder.RoleToRights
import com.normation.rudder.RudderAccount
import com.normation.rudder.api._
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.rest.RoleApiMapping
import com.normation.rudder.web.services.UserSessionLogEvent
import com.normation.utils.HashcodeCaching
import com.typesafe.config.ConfigException
import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import net.liftweb.common._
import org.joda.time.DateTime
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportResource
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.core.io.Resource
import org.springframework.core.io.{ClassPathResource => CPResource}
import org.springframework.core.io.{FileSystemResource => FSResource}
import org.springframework.ldap.core.DirContextAdapter
import org.springframework.ldap.core.DirContextOperations
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.authentication.encoding.Md5PasswordEncoder
import org.springframework.security.authentication.encoding.PlaintextPasswordEncoder
import org.springframework.security.authentication.encoding.ShaPasswordEncoder
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler
import org.xml.sax.SAXParseException

import scala.collection.JavaConverters.asJavaCollectionConverter

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
 * Note that the main Spring application entry point is bootstrap.liftweb.AppConfig
 * which import AppConfigAuth which itself import generic security context and
 * dedicated authentication methods.
 */
@Configuration
@ImportResource(Array("classpath:applicationContext-security.xml"))
class AppConfigAuth extends ApplicationContextAware {
  import AppConfigAuth._
  import RudderProperties.config

  import scala.collection.JavaConverters.seqAsJavaListConverter

  private[this] var appCtx: ApplicationContext = null //yeah...

  val logger = ApplicationLogger

  // we define the System ApiAcl as one that is all mighty and can manage everything
  val SYSTEM_API_ACL = ApiAuthorization.RW

  def setApplicationContext(applicationContext: ApplicationContext): Unit = {
    //prepare specific properties for new context
    import scala.collection.JavaConverters._
    RudderProperties.authenticationMethods.foreach { x =>
      try {
        // try to load all the specific properties of that auth type
        // so that they are available from Spring
        // the config can have 0 specif entry => try/catch
        config.getConfig(s"rudder.auth.${x.name}").entrySet.asScala.foreach { case e =>
          val fullKey = s"rudder.auth.${x.name}.${e.getKey}"
          System.setProperty(fullKey, config.getString(fullKey))
        }
      } catch {
        case ex: ConfigException.Missing => //does nothing - the beauty of imperative prog :(
      }
    }

    //load additionnal beans from authentication dedicated ressource files

    val propertyConfigurer = new PropertyPlaceholderConfigurer()
    propertyConfigurer.setIgnoreResourceNotFound(true)
    propertyConfigurer.setIgnoreUnresolvablePlaceholders(true)
    propertyConfigurer.setSearchSystemEnvironment(true)
    propertyConfigurer.setSystemPropertiesMode(PropertyPlaceholderConfigurer.SYSTEM_PROPERTIES_MODE_OVERRIDE)
    propertyConfigurer.setOrder(10000)

    applicationContext match {
      case x: AbstractApplicationContext =>
        x.addBeanFactoryPostProcessor(propertyConfigurer)

      case _ => //nothing
    }

    val configuredAuthProviders = RudderProperties.authenticationMethods.filter( _.name != "rootAdmin")
    logger.info(s"Loaded authentication provider(s): [${configuredAuthProviders.map(_.name).mkString(", ")}]")

    val ctx = new ClassPathXmlApplicationContext(applicationContext)
    ctx.addBeanFactoryPostProcessor(propertyConfigurer)
    ctx.setConfigLocations(configuredAuthProviders.map( _.configFile ).toArray)
    ctx.refresh
    appCtx = ctx
  }

  ///////////// FOR WEB INTERFACE /////////////

  /**
   * Configure the authentication provider, with always trying
   * the root admin account first so that we always have a way to
   * log-in into Rudder.
   */
  @Bean(name = Array("org.springframework.security.authenticationManager"))
  def authenticationManager = new ProviderManager(RudderProperties.authenticationMethods.map { x =>
      appCtx.getBean(x.springBean, classOf[AuthenticationProvider])
  }.asJava)

  @Bean def rudderWebAuthenticationFailureHandler: AuthenticationFailureHandler = new RudderUrlAuthenticationFailureHandler("/index.html?login_error=true")

  @Bean def rudderUserDetailsService: RudderInMemoryUserDetailsService = {
    try {
      val resource = getUserResourceFile
      //try to read and parse the file for users
      parseUsers(resource) match {
        case Some(config) =>
          new RudderInMemoryUserDetailsService(config.encoder, config.users.map { case (login,pass,roles) =>
            RudderUserDetail(RudderAccount.User(login, pass), roles.toSet, ApiAuthorization.ACL(RoleApiMapping.getApiAclFromRoles(roles)))
          }.toSet)
        case None =>
          ApplicationLogger.error("Error when trying to parse user file '%s', aborting.".format(resource.getURL.toString))
          throw new javax.servlet.UnavailableException("Error when triyng to parse user file '%s', aborting.".format(resource.getURL.toString))
      }
    } catch {
      case e : SAXParseException =>
        ApplicationLogger.error("User definitions: An error occured while parsing /opt/rudder/etc/rudder-users.xml. Logging in to the Rudder web interface will not be possible until this is fixed and the application restarted.")
        ApplicationLogger.error(s"User definitions: XML in file /opt/rudder/etc/rudder-users.xml is incorrect, error message is: ${e.getMessage()} (line ${e.getLineNumber()}, column ${e.getColumnNumber()})")
        throw e
      case e: Exception =>
        ApplicationLogger.error("User definitions: An error occured while parsing /opt/rudder/etc/rudder-users.xml. Logging in to the Rudder web interface will not be possible until this is fixed and the application restarted.")
        ApplicationLogger.error(s"User definitions: Error message is: ${e.getMessage()}")
        throw e
    }
  }

  @Bean def fileAuthenticationProvider : AuthenticationProvider = {
    val provider = new DaoAuthenticationProvider()
    provider.setUserDetailsService(rudderUserDetailsService)
    provider.setPasswordEncoder(rudderUserDetailsService.passwordEncoder)
    provider
  }

  @Bean def rootAdminAuthenticationProvider : AuthenticationProvider = {
    // We want to be able to disable that account.
    // For that, we let the user either let undefined udder.auth.admin.login,
    // or let empty udder.auth.admin.login or rudder.auth.admin.password

    val set = if(config.hasPath("rudder.auth.admin.login") && config.hasPath("rudder.auth.admin.password")) {
      val login = config.getString("rudder.auth.admin.login")
      val password = config.getString("rudder.auth.admin.password")

      if(login.isEmpty || password.isEmpty) {
        Set.empty[RudderUserDetail]
      } else {
        Set(RudderUserDetail(
            RudderAccount.User(
                login
              , password
            )
          , RoleToRights.parseRole(Seq("administrator")).toSet
          , SYSTEM_API_ACL
        ))
      }
    } else {
      Set.empty[RudderUserDetail]
    }

    if(set.isEmpty) {
      logger.info("No master admin account is defined. You can define one with 'rudder.auth.admin.login' and 'rudder.auth.admin.password' properties in the configuration file")
    }

    val provider = new DaoAuthenticationProvider()
    provider.setUserDetailsService(new RudderInMemoryUserDetailsService(new PlaintextPasswordEncoder, set))
    provider
  }

  ///////////// FOR REST API /////////////

  @Bean def restAuthenticationFilter = new RestAuthenticationFilter(RudderConfig.roApiAccountRepository, rudderUserDetailsService, SYSTEM_API_ACL)

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

  //userSessionLogEvent must not be lazy, because not used by anybody directly
  @Bean def userSessionLogEvent = new UserSessionLogEvent(RudderConfig.eventLogRepository, RudderConfig.stringUuidGenerator)

}

/*
 * A trivial extension to the SimpleUrlAuthenticationFailureHandler that correctly log failure
 */
class RudderUrlAuthenticationFailureHandler(failureUrl: String) extends SimpleUrlAuthenticationFailureHandler(failureUrl) {
  override def onAuthenticationFailure(request: HttpServletRequest, response: HttpServletResponse, exception: AuthenticationException): Unit = {
    LogFailedLogin.warn(exception, request)
    super.onAuthenticationFailure(request, response, exception)
  }
}

object LogFailedLogin {

  def warn(ex: AuthenticationException, request: HttpServletRequest): Unit = {
    ApplicationLogger.warn(s"Login authentication failed for user '${getUser(ex)}' from IP '${getRemoteAddr(request)}': ${ex.getMessage}")
  }

  def getUser(ex: AuthenticationException): String = {
    //remove deprecation warning
    @silent def getAuthentication(bce: AuthenticationException) = bce.getAuthentication

    ex match {
      case bce:BadCredentialsException =>
        getAuthentication(bce) match {
          case user: UsernamePasswordAuthenticationToken => user.getName
          case _                                         => "unknown"
        }
      case _ => "unknown"
    }
  }

  def getRemoteAddr(request: HttpServletRequest): String = {
    /*
     * Of course there is not reliable way to get remote address, because of proxy, forging, etc.
     */

    // Some interesting header, get from https://gist.github.com/nioe/11477264
    // the list seems to be roughtly correctly ordered - perhaps we don't want one, but all?
    val headers = List("X-Forwarded-For", "Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR")

    //utility to display correctly the pair header:value
    def show(t2: Tuple2[String, String]) = t2._1+":"+t2._2

    // always returns the ip addr as seen from the server, and the list of other perhaps interesting info
    (
      request.getRemoteAddr ::
      headers.flatMap { header => request.getHeader(header) match {
          case null | "" => None
          case x => if(x.toLowerCase != "unknown") Some((header, x)) else None
        }
      }.groupBy(_._2).map(x => show(x._2.head)).toList
    ).mkString("|")
  }
}

/**
 *  A trivial, immutable implementation of UserDetailsService for RudderUser
 */
class RudderInMemoryUserDetailsService(val passwordEncoder: PasswordEncoder.Rudder, private[this] val _users: Set[RudderUserDetail]) extends UserDetailsService {
  private[this] val users = Map[String, RudderUserDetail](_users.map(u => (u.getUsername, u)).toSeq:_*)

  @throws(classOf[UsernameNotFoundException])
  override def loadUserByUsername(username:String) : RudderUserDetail = {
    users.getOrElse(username, throw new UsernameNotFoundException(s"User with username '${username}' was not found"))
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
 * And one other for API: ROLE_REMOTE
 */
sealed trait RudderAuthType {
  def grantedAuthorities: Collection[GrantedAuthority]
}

final object RudderAuthType {
  // build a GrantedAuthority from the string
  private def buildAuthority(s: String): Collection[GrantedAuthority] = {
    Seq(new GrantedAuthority { override def getAuthority: String = s }).asJavaCollection
  }

  final case object User extends RudderAuthType {
    override val grantedAuthorities = buildAuthority("ROLE_USER")
  }
  final case object Api extends RudderAuthType {
    override val grantedAuthorities = buildAuthority("ROLE_REMOTE")

    val apiRudderRights = new Rights(AuthorizationType.NoRights)
    val apiRudderRole: Set[Role] = Set(Role.NoRights)
  }
}


/**
 * Our simple model for for user authentication and authorizations.
 * Note that authorizations are not managed by spring, but by the
 * 'authz' token of RudderUserDetail.
 */
case class RudderUserDetail(
    account : RudderAccount
  , roles   : Set[Role]
  , apiAuthz: ApiAuthorization
) extends UserDetails with HashcodeCaching {
  // merge roles rights
  val authz = new Rights(roles.flatMap(_.rights.authorizationTypes).toSeq:_*)
  override val (getUsername, getPassword, getAuthorities) = account match {
    case RudderAccount.User(login, password) => (login         , password       , RudderAuthType.User.grantedAuthorities)
    case RudderAccount.Api(api)              => (api.name.value, api.token.value, RudderAuthType.Api.grantedAuthorities)
  }
  override val isAccountNonExpired        = true
  override val isAccountNonLocked         = true
  override val isCredentialsNonExpired    = true
  override val isEnabled                  = true
}

/**
 * Our rest filter, we just look for X-API-Token and
 * ckeck if a token exists with that value.
 * For Account with type "user", we need to also check
 * for the user and update REST token ACL with that knowledge
 */
class RestAuthenticationFilter(
    apiTokenRepository: RoApiAccountRepository
  , userDetailsService: RudderInMemoryUserDetailsService
  , systemApiAcl      : ApiAuthorization
  , apiTokenHeaderName: String = "X-API-Token"
) extends Filter with Loggable {
  def destroy(): Unit = {}
  def init(config: FilterConfig): Unit = {}

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

  private[this] def failsAuthentication(httpRequest: HttpServletRequest, httpResponse: HttpServletResponse, eb: EmptyBox) : Unit = {
    val e = eb ?~! s"REST authentication failed from IP '${LogFailedLogin.getRemoteAddr(httpRequest)}'"
    ApplicationLogger.warn(e.messageChain.replaceAll(" <-", ":"))
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

        httpRequest.getHeader(apiTokenHeaderName) match {

          case null | "" =>

            /* support of API v1 rest.AllowNonAuthenticatedUser */
            if(isValidNonAuthApiV1(httpRequest)) {

              val name = "api-v1-unauthenticated-account"
              val apiV1Account = ApiAccount(
                  ApiAccountId(name)
                , ApiAccountKind.PublicApi(ApiAuthorization.None, None) // un-authenticated APIv1 token certainly doesn't get any authz on v2 API
                , ApiAccountName(name)
                , ApiToken(name)
                , "API Accuount for un-authenticated API"
                , true
                , new DateTime(0)
                , DateTime.now()
              )

              authenticate(RudderUserDetail(
                   RudderAccount.Api(apiV1Account)
                 , RudderAuthType.Api.apiRudderRole
                 , ApiAuthorization.None // un-authenticated APIv1 token certainly doesn't get any authz on v2 API
              ))
              chain.doFilter(request, response)
            } else {
              failsAuthentication(httpRequest, httpResponse, Failure(s"Missing or empty HTTP header '${apiTokenHeaderName}'"))
            }

          case token =>
            //try to authenticate

            val apiToken = ApiToken(token)
            val systemAccount = apiTokenRepository.getSystemAccount
            if (systemAccount.token == apiToken) { // system token with super authz
              authenticate(RudderUserDetail(
                  RudderAccount.Api(systemAccount)
                , Set(Role.Administrator)  // this token has "admin rights - use with care
                , systemApiAcl
              ))

              chain.doFilter(request, response)
            } else { // standard token, try to find it in DB
              apiTokenRepository.getByToken(apiToken) match {
                case eb:EmptyBox =>
                  failsAuthentication(httpRequest, httpResponse, eb)

                case Full(None) =>
                  failsAuthentication(httpRequest, httpResponse, Failure(s"No registered token '${token}'"))

                case Full(Some(principal)) =>

                  if(principal.isEnabled) {
                    principal.kind match {
                      case ApiAccountKind.System => // we don't want to allow system account kind from DB
                        failsAuthentication(httpRequest, httpResponse, Failure(s"A saved API account can not have the kind 'System': '${principal.name}'"))
                      case ApiAccountKind.PublicApi(authz, expirationDate) =>
                        expirationDate match {
                          case Some(date) if(DateTime.now().isAfter(date)) =>
                            failsAuthentication(httpRequest, httpResponse, Failure(s"Account with ID ${principal.id.value} is disabled"))
                          case _ => // no expiration date or expiration date not reached

                            val user = RudderUserDetail(
                                RudderAccount.Api(principal)
                              , RudderAuthType.Api.apiRudderRole
                              , authz
                            )
                            //cool, build an authentication token from it
                            authenticate(user)
                            chain.doFilter(request, response)
                        }
                      case ApiAccountKind.User =>
                        // User account need an update for their ACL.
                        (try {
                          Right(userDetailsService.loadUserByUsername(principal.id.value))
                        } catch {
                          case ex: UsernameNotFoundException => Left(s"User with id '${principal.id.value}' was not found on the system. The API token linked to that user can not be used anymore.")
                          case ex: Exception                 => Left(s"Error when trying to get user information linked to user '${principal.id.value}' API token.")
                        }) match {
                          case Right(u) => //update acl
                            authenticate(RudderUserDetail(
                                RudderAccount.Api(principal)
                              , u.roles
                              , u.apiAuthz
                            ))
                            chain.doFilter(request, response)

                          case Left(er) =>
                            failsAuthentication(httpRequest, httpResponse, Failure(er))
                        }
                    }
                  } else {
                    failsAuthentication(httpRequest, httpResponse, Failure(s"Account with ID ${principal.id.value} is disabled"))
                  }
              }
            }
        }

      case _ =>
        //can not do anything with that, chain filter.
        chain.doFilter(request, response)
    }

  }
}

//remove deprecation warning on `PasswordEncoder`
@silent object PasswordEncoder {
  type Rudder = org.springframework.security.authentication.encoding.PasswordEncoder
}

@silent case class AuthConfig(
  encoder: PasswordEncoder.Rudder,
  users:List[(String,String,Seq[Role])]
) extends HashcodeCaching

class RudderXmlUserDetailsContextMapper(authConfig: AuthConfig) extends UserDetailsContextMapper {

  val users = authConfig.users.map { case(login,pass,roles) =>
    (login, RudderUserDetail(RudderAccount.User(login, pass), roles.toSet, ApiAuthorization.ACL(RoleApiMapping.getApiAclFromRoles(roles))))
  }.toMap

  //we are not able to try to save user in the XML file
  def mapUserToContext(user: UserDetails, ctx: DirContextAdapter) : Unit = ()

  def mapUserFromContext(ctx: DirContextOperations, username: String, authorities: Collection[_ <:GrantedAuthority]): UserDetails = {
    users.getOrElse(username, RudderUserDetail(RudderAccount.User(username, ""), Set(Role.NoRights), ApiAuthorization.None))
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
           , node.attribute("role").map(_.toList.map( role => RoleToRights.parseRole(role.text.split(",").toSeq.map(_.trim))))
         ) match {
           case (Some(name :: Nil) , Some(pwd :: Nil), roles ) if(name.size > 0 && pwd.size > 0) => roles match {
             case Some(roles:: Nil) => (name, pwd, roles) :: Nil
             case _ =>  (name, pwd, Seq(Role.NoRights)) :: Nil
           }

           case _ =>
             ApplicationLogger.error(s"Ignore user line in authentication file '${resource.getURL.toString}', some required attribute is missing: ${node.toString}")
             Nil
         }
        })

        //and now, return the list of users
        users map { user =>
        if (user._3.contains(Role.NoRights))
          ApplicationLogger.warn(s"User '${user._1}' authorisation are not defined correctly, please fix it (defined authorizations: ${user._3.map(_.name.toLowerCase()).mkString(", ")})")
        ApplicationLogger.debug(s"User '${user._1}' with defined authorizations: ${user._3.map(_.name.toLowerCase()).mkString(", ")}")
        }
        Some(AuthConfig(hash, users))
      }
    } else {
      ApplicationLogger.error("The resource '%s' does not exist or is not readable".format(resource.getURL.toString))
      None
    }
  }
}
