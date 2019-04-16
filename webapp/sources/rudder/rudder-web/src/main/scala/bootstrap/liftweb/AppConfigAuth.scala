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

import java.util.Collection

import com.normation.rudder.Role
import com.normation.rudder.RoleToRights
import com.normation.rudder.RudderAccount
import com.normation.rudder.api._
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.web.services.UserSessionLogEvent
import com.typesafe.config.Config
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
import org.springframework.ldap.core.DirContextAdapter
import org.springframework.ldap.core.DirContextOperations
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
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

import com.normation.zio._
import com.normation.errors._

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
 * Note that the main Spring application entry point is bootstrap.liftweb.AppConfigAuth
 * which itself import generic security context and dedicated authentication methods.
 */
@Configuration
@ImportResource(Array("classpath:applicationContext-security.xml"))
class AppConfigAuth extends ApplicationContextAware {
  import RudderProperties.config

  val logger = ApplicationLogger

  // we define the System ApiAcl as one that is all mighty and can manage everything
  val SYSTEM_API_ACL = ApiAuthorization.RW



  /*
   * This method is expected to try to initialize the Spring bean for
   * all authentication provider confgured by the user for \rudder.auth.provider`.
   * (we don't consider other possible existing backend to avoid errors on
   * not configured backends).
   * If any of the provider from the user list is unknown, then an error log is logged.
   * It will give back an ordered map of name -> AuthenticationMethods to the
   * AuthBackendProvidersManager.
   * It is the DefaultAuthBackendProviders which is responsible to dynamically
   * choose from the map and its own rules (plugin license, etc) what
   * provider are allowed in authentication, dynamically. This is done
   * when the RudderProviderManager ask for DynamicRudderProviderManager#getEnabledProviders.
   *
   * Summary:
   * - spring init is in charge of initializing all configured provider bean, whatever
   *   the plugins status (if any).
   * - spring init gives these provider to DefaultAuthBackendProviders
   * - AuthBackendProvidersManager is in charge to be the link with plugins to know
   *   what is enabled and when.
   */
  def setApplicationContext(applicationContext: ApplicationContext): Unit = {
    // the list of authentication backend configured by the user
    val configuredAuthProviders = AuthenticationMethods.getForConfig(config)



    //prepare specific properties for each configuredAuthProviders - we need system properties for spring

    import scala.collection.JavaConverters._
    configuredAuthProviders.foreach { x =>
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

    logger.info(s"Configured authentication provider(s): [${configuredAuthProviders.map(_.name).mkString(", ")}]")

    val ctx = new ClassPathXmlApplicationContext(applicationContext)
    ctx.addBeanFactoryPostProcessor(propertyConfigurer)
    // here, we load all spring bean conigured in the classpath files: applicationContext-security-auth-BACKEND.xml
    ctx.setConfigLocations(configuredAuthProviders.map( _.configFile ).toSeq:_*)
    ctx.refresh

    // now, handle back the configured bean / user configured list to AuthBackendProvidersManager
    configuredAuthProviders.foreach { provider =>
      try {
        val bean = ctx.getBean(provider.springBean, classOf[AuthenticationProvider])
        RudderConfig.authenticationProviders.addSpringAuthenticationProvider(provider.name, bean)
      } catch {
        case ex: Exception =>
          ApplicationLogger.error(s"Error when trying to configure the authentication backend '${provider.name}': ${ex.getMessage}", ex)
      }
    }
    RudderConfig.authenticationProviders.setConfiguredProviders(configuredAuthProviders.toArray)
  }

  ///////////// FOR WEB INTERFACE /////////////

  /**
   * Configure the authentication provider, with always trying
   * the root admin account first so that we always have a way to
   * log-in into Rudder.
   */
  @Bean(name = Array("org.springframework.security.authenticationManager"))
  def authenticationManager = new RudderProviderManager(RudderConfig.authenticationProviders)

  @Bean def rudderWebAuthenticationFailureHandler: AuthenticationFailureHandler = new RudderUrlAuthenticationFailureHandler("/index.html?login_error=true")

  @Bean def rudderUserDetailsService: RudderInMemoryUserDetailsService = {
    new RudderInMemoryUserDetailsService(RudderConfig.rudderUserListProvider)
  }

  @Bean def fileAuthenticationProvider : AuthenticationProvider = {
    val provider = new DaoAuthenticationProvider()
    provider.setUserDetailsService(rudderUserDetailsService)
    provider.setPasswordEncoder(rudderUserDetailsService.authConfigProvider.authConfig.encoder)
    // we need to register a callback to update password encoder when needed
    RudderConfig.rudderUserListProvider.registerCallback( cb => provider.setPasswordEncoder(cb.encoder) )
    provider
  }

  @Bean def rootAdminAuthenticationProvider : AuthenticationProvider = {
    // We want to be able to disable that account.
    // For that, we let the user either let undefined udder.auth.admin.login,
    // or let empty udder.auth.admin.login or rudder.auth.admin.password

    val admins = if(config.hasPath("rudder.auth.admin.login") && config.hasPath("rudder.auth.admin.password")) {
      val login = config.getString("rudder.auth.admin.login")
      val password = config.getString("rudder.auth.admin.password")

      if(login.isEmpty || password.isEmpty) {
        Map.empty[String, RudderUserDetail]
      } else {
        Map(login -> RudderUserDetail(
            RudderAccount.User(
                login
              , password
            )
          , RoleToRights.parseRole(Seq("administrator")).toSet
          , SYSTEM_API_ACL
        ))
      }
    } else {
      Map.empty[String, RudderUserDetail]
    }

    if(admins.isEmpty) {
      logger.info("No master admin account is defined. You can define one with 'rudder.auth.admin.login' and 'rudder.auth.admin.password' properties in the configuration file")
    }

    val passwordEncoder = PasswordEncoder.PlainText
    val authConfigProvider = new UserDetailListProvider {
      override def authConfig: UserDetailList = UserDetailList(passwordEncoder, admins)
    }
    val provider = new DaoAuthenticationProvider()
    provider.setUserDetailsService(new RudderInMemoryUserDetailsService(authConfigProvider))
    provider.setPasswordEncoder(passwordEncoder) // force password encode to plaintext only
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
    new RudderXmlUserDetailsContextMapper(RudderConfig.rudderUserListProvider)
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
    ApplicationLogger.warn(s"Login authentication failed for user '${getUser(request)}' from IP '${getRemoteAddr(request)}': ${ex.getMessage}")
  }

  // user login is passed in parameters named "j_username"
  def getUser(req: HttpServletRequest): String = {
    req.getParameter("username") match {
      case null  => "unknown"
      case login => login
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
class RudderInMemoryUserDetailsService(val authConfigProvider: UserDetailListProvider) extends UserDetailsService {
  @throws(classOf[UsernameNotFoundException])
  override def loadUserByUsername(username:String) : RudderUserDetail = {
    authConfigProvider.authConfig.users.getOrElse(username, throw new UsernameNotFoundException(s"User with username '${username}' was not found"))
  }
}

/**
 * Spring context mapper
 */
class RudderXmlUserDetailsContextMapper(authConfigProvider: UserDetailListProvider) extends UserDetailsContextMapper {
  //we are not able to try to save user in the XML file
  def mapUserToContext(user: UserDetails, ctx: DirContextAdapter) : Unit = ()

  def mapUserFromContext(ctx: DirContextOperations, username: String, authorities: Collection[_ <:GrantedAuthority]): UserDetails = {
    authConfigProvider.authConfig.users.getOrElse(username, RudderUserDetail(RudderAccount.User(username, ""), Set(Role.NoRights), ApiAuthorization.None))
  }
}

/**
 * This is the class that defines the authentication methods.
 * Without the plugin, by default only "file" is known.
 */
trait AuthBackendsProvider {
  def authenticationBackends: Set[String]
  def name: String
  // dynamically check if a given plugin is enable
  def allowedToUseBackend(name: String): Boolean
}

final case class AuthenticationMethods(name: String) {
  val path = s"applicationContext-security-auth-${name}.xml"
  val configFile = s"classpath:${path}"
  val springBean = s"${name}AuthenticationProvider"
}

object AuthenticationMethods {
  //some logic for the authentication providers
  def getForConfig(config: Config): Seq[AuthenticationMethods] = {
    val names = {
      try {
        //config.getString can't be null by contract
        config.getString("rudder.auth.provider").split(",").toSeq.map( _.trim).collect { case s if(s.size > 0) => s}
      } catch {
        //if the property is missing, use the default "file" value
        //it can be a migration.
        case ex: ConfigException.Missing =>  Seq("file")
      }
    }

    //always add "rootAdmin" has the first method
    //and de-duplicate methods
    val auths = ("rootAdmin" +: names).distinct.map(AuthenticationMethods(_))

    //for each methods, check that the provider file is present, or log an error and
    //disable that provider
    auths.flatMap { a =>
      if(a.name == "rootAdmin") {
        Some(a)
      } else {
        //try to instantiate
        val cpr = new org.springframework.core.io.ClassPathResource(a.path)
        if(cpr.exists) {
          Some(a)
        } else {
          ApplicationLogger.error(s"The authentication provider '${a.name}' will not be loaded because the spring " +
                                  s"ressource file '${a.configFile}' was not found. Perhaps are you missing a plugin?")
          None
        }
      }
    }
  }
}

// and default implementation: provides 'file', 'rootAdmin'
class AuthBackendProvidersManager() extends DynamicRudderProviderManager {

  val defaultAuthBackendsProvider = new AuthBackendsProvider() {
    override def authenticationBackends: Set[String] = Set("file", "rootAdmin")
    override def name: String = s"Default authentication backends provider: '${authenticationBackends.mkString("','")}"
    override def allowedToUseBackend(name: String): Boolean = true // always enable - ie we never want to skip them
  }

  // the list of AuthenticationMethods configured by the user
  private[this] var authenticationMethods = Array[AuthenticationMethods]() // must be a var/array, because init by spring-side

  private[this] var backends = Seq[AuthBackendsProvider]()
  // a map of status for each backend (status is dynamic)
  private[this] var allowedToUseBackend = Map[String, () => Boolean]()

  // this is the map of configured spring bean "AuthenticationProvider"
  private[this] var springProviders = Map[String, AuthenticationProvider]()

  def addProvider(p: AuthBackendsProvider): Unit = {
    ApplicationLogger.info(s"Add backend providers '${p.name}'")
    backends = backends :+ p
    allowedToUseBackend = allowedToUseBackend ++ ( p.authenticationBackends.map(name => (name, () => p.allowedToUseBackend(name))) )
  }

  // add default providers
  this.addProvider(defaultAuthBackendsProvider)

  /*
   * Add a spring configured name -> provider
   */
  def addSpringAuthenticationProvider(name: String, provider: AuthenticationProvider): Unit = {
    this.springProviders = this.springProviders + (name -> provider)
  }

  /*
   * get the list of providers currently configured by user. Array because used from spring
   */
  def setConfiguredProviders(providers: Array[AuthenticationMethods]): Unit = {
    this.authenticationMethods = providers
  }

  // get the list of provider in an imutable seq
  def getConfiguredProviders(): Seq[AuthenticationMethods] = {
    this.authenticationMethods.toSeq
  }

  /*
   * what we
   */
  override def getEnabledProviders(): Array[AuthenticationProvider] = {
    authenticationMethods.flatMap { m =>
      if(allowedToUseBackend.isDefinedAt(m.name)) {
        if(allowedToUseBackend(m.name)() == true) {
          springProviders.get(m.name)
        } else {
          ApplicationLogger.debug(s"Authentication backend '${m.name}' is not currently enable. Perhaps a plugin is not enabled?")
          None
        }
      } else {
        ApplicationLogger.debug(s"Authentication backend '${m.name}' was not found in available backends. Perhaps a plugin is missing?")
        None
      }
    }
  }
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

  private[this] def failsAuthentication(httpRequest: HttpServletRequest, httpResponse: HttpServletResponse, error: RudderError) : Unit = {
    val msg = s"REST authentication failed from IP '${LogFailedLogin.getRemoteAddr(httpRequest)}'. Error was: ${error.fullMsg}"
    ApplicationLogger.warn(msg)
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
        val token = httpRequest.getHeader(apiTokenHeaderName);
        token match {
          case null | "" =>
            /* support of API v1 rest.AllowNonAuthenticatedUser */
            if(isValidNonAuthApiV1(httpRequest)) {
              val name = "api-v1-unauthenticated-account"
              val apiV1Account = ApiAccount(
                  ApiAccountId(name)
                , ApiAccountKind.PublicApi(ApiAuthorization.None, None) // un-authenticated APIv1 token certainly doesn't get any authz on v2 API
                , ApiAccountName(name)
                , ApiToken(name)
                , "API Account for un-authenticated API"
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
              failsAuthentication(httpRequest, httpResponse, Unconsistancy(s"Missing or empty HTTP header '${apiTokenHeaderName}'"))
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
              apiTokenRepository.getByToken(apiToken).either.runNow match {
                case Left(err) =>
                  failsAuthentication(httpRequest, httpResponse, err)

                case Right(None) =>
                  failsAuthentication(httpRequest, httpResponse, Unconsistancy(s"No registered token '${token}'"))

                case Right(Some(principal)) =>

                  if(principal.isEnabled) {
                    principal.kind match {
                      case ApiAccountKind.System => // we don't want to allow system account kind from DB
                        failsAuthentication(httpRequest, httpResponse, Unconsistancy(s"A saved API account can not have the kind 'System': '${principal.name}'"))
                      case ApiAccountKind.PublicApi(authz, expirationDate) =>
                        expirationDate match {
                          case Some(date) if(DateTime.now().isAfter(date)) =>
                            failsAuthentication(httpRequest, httpResponse, Unconsistancy(s"Account with ID ${principal.id.value} is disabled"))
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
                            failsAuthentication(httpRequest, httpResponse, Unconsistancy(er))
                        }
                    }
                  } else {
                    failsAuthentication(httpRequest, httpResponse, Unconsistancy(s"Account with ID ${principal.id.value} is disabled"))
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
