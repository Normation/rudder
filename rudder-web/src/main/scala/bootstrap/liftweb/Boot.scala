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

import net.liftweb.http._
import net.liftweb.common._
import net.liftweb.sitemap._
import net.liftweb.sitemap.Loc._
import com.normation.plugins.RudderPluginDef
import com.normation.rudder.domain.eventlog.ApplicationStarted
import com.normation.rudder.web.rest._
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLog
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.authorization._
import com.normation.authorization.AuthorizationType
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.eventlog.ModificationId
import java.util.Locale
import net.liftweb.http.rest.RestHelper
import org.joda.time.DateTime
import com.normation.rudder.web.snippet.WithCachedResource
import java.net.URLConnection

/*
 * Utilities about rights
 */
object Boot {
  val redirection = RedirectState(() => (), "You are not authorized to access that page, please contact your administrator." -> NoticeType.Error )

  def userIsAllowed(redirectTo:String, requiredAuthz:AuthorizationType*) : Box[LiftResponse] =  {
    if (requiredAuthz.exists((CurrentUser.checkRights(_)))) {
      Empty
    } else {
      Full(RedirectWithState(redirectTo, redirection ) )
    }
  }
}

////////// rewrites rules to remove the version from resources urls //////////
//////////
object StaticResourceRewrite extends RestHelper {
  // prefix added to signal that the resource is cached
  val prefix = s"cache-${RudderConfig.rudderFullVersion}"
  def headers(others: List[(String,String)]): List[(String,String)] = {
    ("Cache-Control", "max-age=31556926, public") ::
    ("Pragma", "") ::
    ("Expires", DateTime.now.plusMonths(6).toString("EEE, d MMM yyyy HH':'mm':'ss 'GMT'")) ::
    others
 }

  //the resource directory we want to server that way
  val resources = Set("javascript", "style", "images")
  serve {
    case Get(prefix :: resource :: tail,  req) if(resources.contains(resource)) =>
      val resourcePath = req.uri.replaceFirst(prefix+"/", "")
      () => {
        for {
          url <- LiftRules.getResource(resourcePath)
        } yield {
          val contentType = URLConnection.guessContentTypeFromName(url.getFile) match {
            // if we don't know the content type, skip the header: most of the time,
            // browsers can live whithout it, but can't with a bad value in it
            case null                        => Nil
            case x if(x.contains("unknown")) => Nil
            case x                           => ("Content-Type", x) :: Nil
          }
          val conn = url.openConnection //will be local, so ~ efficient
          val size = conn.getContentLength
          val in = conn.getInputStream
          StreamingResponse(in, () => in.close, size = size, headers(contentType),  cookies = Nil, code=200)
        }
      }
  }
}

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class Boot extends Loggable {

  import Boot._

  def boot() {

    // Set locale to English to prevent having localized message in some exception message (like SAXParserException in AppConfigAuth).
    // For now we don't manage locale in Rudder so setting it to English is harmless.
    // If one day we handle it in Rudder we should start from here by modifying code here..
    Locale.setDefault(Locale.ENGLISH)

    LiftRules.early.append( {req: provider.HTTPRequest => req.setCharacterEncoding("UTF-8")})
    LiftRules.ajaxStart = Full(() => LiftRules.jsArtifacts.show("ajax-loader").cmd)
    LiftRules.ajaxEnd = Full(() => LiftRules.jsArtifacts.hide("ajax-loader").cmd)
    LiftRules.ajaxPostTimeout = 30000

    // We don't want to reload the page
    LiftRules.redirectAsyncOnSessionLoss = false;
    //we don't want to retry on ajax timeout, as it may have big consequence
    //when it's (for example) a deploy

    logger.info(LiftRules.resourceServerPath)
    LiftRules.ajaxRetryCount = Full(1)

    // where to search snippet
    LiftRules.addToPackages("com.normation.rudder.web")

    //exclude Rudder doc from context-path rewriting
    LiftRules.excludePathFromContextPathRewriting.default.set(() => (path:String) => {
      val noRedirectPaths= "/rudder-doc" :: "/ncf" :: "/ncf-builder" :: Nil
      noRedirectPaths.exists(path.startsWith)
    })

    ////////// CACHE INVALIDATION FOR RESOURCES //////////
    // fails on invalid JSON body because it's unsufferable
    LiftRules.statelessDispatch.append {
      case req: Req if(   req.json_?
                       && req.requestType != GetRequest
                       && req.requestType != HeadRequest
                       && req.requestType != OptionsRequest
                       && req.json != null
                       && req.json.isEmpty
        ) =>
        () => Full(
          JsonResponse(net.liftweb.json.parse(
            """{"result": "error"
              |, "errorDetails": "The request has a JSON content type but the body is not valid JSON"}""".stripMargin
          ), 412)
        )
    }
    // Resolve resources prefixed with the cache resource prefix
    LiftRules.statelessDispatch.append(StaticResourceRewrite)
    // and tell lift to happen rudder version when "with-resource-id" is used
    LiftRules.attachResourceId = (path: String) => { "/" + StaticResourceRewrite.prefix + path }
    LiftRules.snippetDispatch.append(Map("with-cached-resource" -> WithCachedResource))

    // REST API
    LiftRules.statelessDispatch.append(RestStatus)
    LiftRules.statelessDispatch.append(RestAuthentication)
    LiftRules.statelessDispatch.append(RudderConfig.restDeploy)
    LiftRules.statelessDispatch.append(RudderConfig.restDyngroupReload)
    LiftRules.statelessDispatch.append(RudderConfig.restTechniqueReload)
    LiftRules.statelessDispatch.append(RudderConfig.restArchiving)
    LiftRules.statelessDispatch.append(RudderConfig.restGetGitCommitAsZip)
    LiftRules.statelessDispatch.append(RudderConfig.restApiAccounts)
    LiftRules.statelessDispatch.append(RudderConfig.restQuicksearch)
    LiftRules.statelessDispatch.append(RudderConfig.restCompletion)
    LiftRules.statelessDispatch.append(RudderConfig.apiDispatcher)

    // Intern API
    LiftRules.statelessDispatch.append(RudderConfig.sharedFileApi)

    // URL rewrites
    LiftRules.statefulRewrite.append {
      case RewriteRequest(ParsePath("secure" :: "administration" :: "techniqueLibraryManagement" :: activeTechniqueId :: Nil, _, _, _), GetRequest, _) =>
        RewriteResponse("secure" :: "administration" :: "techniqueLibraryManagement" :: Nil, Map("techniqueId" -> activeTechniqueId))
      case RewriteRequest(ParsePath("secure"::"nodeManager"::"searchNodes"::nodeId::Nil, _, _, _), GetRequest, _) =>
        RewriteResponse("secure"::"nodeManager"::"searchNodes"::Nil, Map("nodeId" -> nodeId))
      case RewriteRequest(ParsePath("secure"::"utilities"::"changeRequests"::filter::Nil, _, _, _), GetRequest, _) =>
        RewriteResponse("secure"::"utilities"::"changeRequests"::Nil, Map("filter" -> filter))
      case RewriteRequest(ParsePath("secure"::"utilities"::"changeRequest"::crId::Nil, _, _, _), GetRequest, _) =>
        RewriteResponse("secure"::"utilities"::"changeRequest"::Nil, Map("crId" -> crId))
    }

    // Fix relative path to css resources
    LiftRules.fixCSS("style" :: "style" :: Nil, Empty)

    // i18n
    LiftRules.resourceNames = "default" :: "ldapObjectAndAttributes" :: "eventLogTypeNames" :: Nil

    // Content type things : use text/html in place of application/xhtml+xml
    LiftRules.useXhtmlMimeType = false

    // Lift 3 add security rules. It's good! But we use a lot
    // of server side generated js and other things that make
    // it extremelly impracticable for us.
    // allows everything and do not log in prod mode problems
    LiftRules.securityRules = () => SecurityRules(
        https               = None
      , content             = None
      , frameRestrictions   = None
      , enforceInOtherModes = false
      , logInOtherModes     = false
      , enforceInDevMode    = false
      , logInDevMode        = true  // this is to check that nothing is reported on dev.
    )

    /*
     * For development, we override the default local calculator
     * to allow explicit locale switch with just the addition
     * of &locale=en at the end of urls
     */
    import net.liftweb.http.provider.HTTPRequest
    import java.util.Locale
    val DefaultLocale = new Locale("")
    LiftRules.localeCalculator = {(request : Box[HTTPRequest]) => {
      request match {
        case Empty | Failure(_,_,_) => DefaultLocale
        case Full(r) => r.param("locale") match {
          case Nil => DefaultLocale
          case loc::tail => {
            logger.debug("Switch to locale: " + loc)
            loc.split("_").toList match {
              case Nil => DefaultLocale
              case lang::Nil => new Locale(lang)
              case lang::country::tail => new Locale(lang,country)
            }
          }
        }
      }
    }}

    // All the following is related to the sitemap
    val nodeManagerMenu =
      Menu("NodeManagerHome", <i class="fa fa-sitemap"></i> ++ <span>Node management</span>) /
        "secure" / "nodeManager" / "index"  >> TestAccess( ()
            => userIsAllowed("/secure/index",Read("node")) ) submenus (

          Menu("List Nodes", <span>List nodes</span>) /
            "secure" / "nodeManager" / "nodes"
            >> LocGroup("nodeGroup")

        , Menu("SearchNodes", <span>Search nodes</span>) /
            "secure" / "nodeManager" / "searchNodes"
            >> LocGroup("nodeGroup")

        , Menu("ManageNewNode", <span>Accept new nodes</span>) /
            "secure" / "nodeManager" / "manageNewNode"
            >>  LocGroup("nodeGroup")

        , Menu("Groups", <span>Groups</span>) /
            "secure" / "nodeManager" / "groups"
            >> LocGroup("groupGroup")
            >> TestAccess( () => userIsAllowed("/secure/index",Read("group") ) )

      )

    def buildManagerMenu(name:String) =
      Menu(name+"ManagerHome", <i class="fa fa-gears"></i> ++ <span>{name.capitalize} policy</span>) /
        "secure" / (name+"Manager") / "index" >> TestAccess ( ()
            => userIsAllowed("/secure/index",Read("configuration")) ) submenus (

          Menu(name+"RuleManagement", <span>Rules</span>) /
            "secure" / (name+"Manager") / "ruleManagement"
            >> LocGroup(name+"Group")
            >> TestAccess( () => userIsAllowed("/secure/index",Read("rule") ) )

        , Menu(name+"DirectiveManagement", <span>Directives</span>) /
            "secure" / (name+"Manager") / "directiveManagement"
            >> LocGroup(name+"Group")
            >> TestAccess( () => userIsAllowed("/secure/index",Read("directive") ) )

        , Menu(name+"ParameterManagement", <span>Parameters</span>) /
            "secure" / (name+"Manager") / "parameterManagement"
            >> LocGroup(name+"Group")
            >> TestAccess( () => userIsAllowed("/secure/index",Read("directive") ) )
      )

    def administrationMenu =
      Menu("AdministrationHome", <i class="fa fa-gear"></i> ++ <span>Settings</span>) /
        "secure" / "administration" / "index" >> TestAccess ( ()
            => userIsAllowed("/secure/index",Read("administration"), Read("technique")) ) submenus (

          Menu("policyServerManagement", <span>General</span>) /
            "secure" / "administration" / "policyServerManagement"
            >> LocGroup("administrationGroup")
            >> TestAccess ( () => userIsAllowed("/secure/index",Read("administration")) )

        , Menu("pluginManagement", <span>Plugins</span>) /
            "secure" / "administration" / "pluginManagement"
            >> LocGroup("administrationGroup")
            >> TestAccess ( () => userIsAllowed("/secure/administration/policyServerManagement",Read("administration")) )

        , Menu("databaseManagement", <span>Reports database</span>) /
            "secure" / "administration" / "databaseManagement"
            >> LocGroup("administrationGroup")
            >> TestAccess ( () => userIsAllowed("/secure/administration/policyServerManagement",Read("administration")) )

        , Menu("TechniqueLibraryManagement", <span>Techniques</span>) /
            "secure" / "administration" / "techniqueLibraryManagement"
            >> LocGroup("administrationGroup")
            >> TestAccess( () => userIsAllowed("/secure/index",Read("technique") ) )
        , Menu("apiManagement", <span>API accounts</span>) /
            "secure" / "administration" / "apiManagement"
            >> LocGroup("administrationGroup")
            >> TestAccess ( () => userIsAllowed("/secure/administration/policyServerManagement",Write("administration")) )
      )

    def utilitiesMenu = {
      // if we can't get the workflow property, default to false
      // (don't give rights if you don't know)
      def workflowEnabled = RudderConfig.configService.rudder_workflow_enabled.getOrElse(false)
      Menu("UtilitiesHome", <i class="fa fa-wrench"></i> ++ <span>Utilities</span>) /
        "secure" / "utilities" / "index" >>
        TestAccess ( () =>
          if ((workflowEnabled && (CurrentUser.checkRights(Read("validator")) || CurrentUser.checkRights(Read("deployer")))) || CurrentUser.checkRights(Read("administration")) || CurrentUser.checkRights(Read("technique")))
            Empty
          else
             Full(RedirectWithState("/secure/index", redirection))
        ) submenus (

          Menu("archivesManagement", <span>Archives</span>) /
            "secure" / "utilities" / "archiveManagement"
            >> LocGroup("utilitiesGroup")
            >> TestAccess ( () => userIsAllowed("/secure/utilities/eventLogs",Write("administration")) )

        , Menu("changeRequests", <span>Change requests</span>) /
            "secure" / "utilities" / "changeRequests"
            >> LocGroup("utilitiesGroup")
            >> TestAccess ( () =>
              if (workflowEnabled && (CurrentUser.checkRights(Read("validator")) || CurrentUser.checkRights(Read("deployer"))))
                Empty
              else
                Full(RedirectWithState("/secure/utilities/eventLogs", redirection ) )
              )

        , Menu("changeRequest", <span>Change request</span>) /
            "secure" / "utilities" / "changeRequest"
            >> Hidden
            >> TestAccess ( () =>
              if (workflowEnabled && (CurrentUser.checkRights(Read("validator")) || CurrentUser.checkRights(Read("deployer"))))
                Empty
              else
                Full(RedirectWithState("/secure/utilities/eventLogs", redirection) )
              )

        , Menu("eventLogViewer", <span>Event logs</span>) /
            "secure" / "utilities" / "eventLogs"
            >> LocGroup("utilitiesGroup")
            >> TestAccess ( () => userIsAllowed("/secure/index",Read("administration")) )

        , Menu("techniqueEditor", <span>Technique editor</span>) /
            "secure" / "utilities" / "techniqueEditor"
            >> LocGroup("utilitiesGroup")
            >> TestAccess ( () => userIsAllowed("/secure/index",Read("technique")) )
      )
    }

    val rootMenu = List(
        Menu("Dashboard", <i class="fa fa-dashboard"></i> ++ <span>Dashboard</span>) / "secure" / "index"
      , Menu("Login") / "index" >> Hidden
      , Menu("Templates") / "templates" / ** >> Hidden //allows access to html file use by js
      , nodeManagerMenu
      , buildManagerMenu("configuration")
      , utilitiesMenu
      , administrationMenu
    ).map( _.toMenu )

    ////////// import and init modules //////////
    val newSiteMap = initPlugins(rootMenu.map( _.toMenu ))

    //not sur why we are using that ?
    //SiteMap.enforceUniqueLinks = false

    LiftRules.setSiteMapFunc(() => SiteMap(newSiteMap:_*))

    RudderConfig.eventLogRepository.saveEventLog(
        ModificationId(RudderConfig.stringUuidGenerator.newUuid)
      , ApplicationStarted(
            EventLogDetails(
                modificationId = None
              , principal = com.normation.rudder.domain.eventlog.RudderEventActor
              , details = EventLog.emptyDetails
              , reason = None
            )
        )
    ) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to save the EventLog for application start"
        ApplicationLogger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex =>
          ApplicationLogger.error("Exception was:", ex)
        }
      case _ => ApplicationLogger.info("Application Rudder started")
    }

  }

  private[this] def initPlugins(menus:List[Menu]) : List[Menu] = {

    //LiftSpringApplicationContext.springContext.refresh
    import scala.collection.JavaConverters._
    val pluginDefs = LiftSpringApplicationContext.springContext.getBeansOfType(classOf[RudderPluginDef]).values.asScala

    pluginDefs.foreach { plugin =>
      initPlugin(plugin)
    }

    //return the updated siteMap
    (menus /: pluginDefs){ case (prev, mutator) => mutator.updateSiteMap(prev) }
  }

  private[this] def initPlugin[T <: RudderPluginDef](plugin:T) : Unit = {

    ApplicationLogger.debug("TODO: manage one time initialization for plugin: " + plugin.id)
    ApplicationLogger.info("Initializing plugin '%s' [%s]".format(plugin.name.value, plugin.id))
    plugin.init
    //add the plugin packages to Lift package to look for packages
    LiftRules.addToPackages(plugin.basePackage)
  }
}
