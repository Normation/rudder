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

import scala.xml._
import net.liftweb.util._
import net.liftweb.http._
import net.liftweb.common._
import net.liftweb.sitemap._
import net.liftweb.sitemap.Loc._
import Helpers._
import net.liftweb.http.js.jquery.JQuery14Artifacts
import net.liftmodules.widgets.autocomplete.AutoComplete
import javax.servlet.UnavailableException
import LiftSpringApplicationContext.inject
import com.normation.plugins.RudderPluginDef
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.domain.eventlog.ApplicationStarted
import com.normation.rudder.web.rest._
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLog
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.authorization._
import com.normation.authorization.AuthorizationType
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.utils.StringUuidGenerator
import com.normation.eventlog.ModificationId
/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class Boot extends Loggable {
  def boot {

    ////////// bootstraps checks //////////
    val checks = RudderConfig.allBootstrapChecks
    checks.checks()

    LiftRules.early.append( {req: provider.HTTPRequest => req.setCharacterEncoding("UTF-8")})
    LiftRules.ajaxStart = Full(() => LiftRules.jsArtifacts.show("ajax-loader").cmd)
    LiftRules.ajaxEnd = Full(() => LiftRules.jsArtifacts.hide("ajax-loader").cmd)
    LiftRules.ajaxPostTimeout = 30000

    // We don't want to reload the page
    LiftRules.redirectAsyncOnSessionLoss = false;
    //we don't want to retry on ajax timeout, as it may have big consequence
    //when it's (for example) a deploy
    LiftRules.ajaxRetryCount = Full(1)

    // where to search snippet
    LiftRules.addToPackages("com.normation.rudder.web")

    // REST API
    LiftRules.statelessDispatch.append(RestStatus)
    LiftRules.statelessDispatch.append(RudderConfig.restDeploy)
    LiftRules.statelessDispatch.append(RudderConfig.restDyngroupReload)
    LiftRules.statelessDispatch.append(RudderConfig.restTechniqueReload)
    LiftRules.statelessDispatch.append(RudderConfig.restArchiving)
    LiftRules.statelessDispatch.append(RudderConfig.restGetGitCommitAsZip)

    // URL rewrites
    LiftRules.statefulRewrite.append {
      case RewriteRequest(ParsePath("secure" :: "configurationManager" :: "techniqueLibraryManagement" :: activeTechniqueId :: Nil, _, _, _), GetRequest, _) =>
        RewriteResponse("secure" :: "configurationManager" :: "techniqueLibraryManagement" :: Nil, Map("techniqueId" -> activeTechniqueId))
      case RewriteRequest(ParsePath("secure"::"nodeManager"::"searchNodes"::nodeId::Nil, _, _, _), GetRequest, _) =>
        RewriteResponse("secure"::"nodeManager"::"searchNodes"::Nil, Map("nodeId" -> nodeId))
    }

    // Fix relative path to css resources
    LiftRules.fixCSS("style" :: "style" :: Nil, Empty)


    // i18n
    LiftRules.resourceNames = "default" :: "ldapObjectAndAttributes" :: "eventLogTypeNames" :: Nil

    // Content type things : use text/html in place of application/xhtml+xml
    LiftRules.useXhtmlMimeType = false

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


    //init autocomplete widget
    AutoComplete.init()


    // All the following is related to the sitemap


    val nodeManagerMenu =
      Menu("NodeManagerHome", <span>Node Management</span>) /
        "secure" / "nodeManager" / "index"  >> TestAccess( ()
            => userIsAllowed(Read("node")) ) submenus (

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
            >> TestAccess( () => userIsAllowed(Read("group") ) )

        //Menu(Loc("PolicyServers", List("secure", "nodeManager","policyServers"), <span>Rudder server</span>,  LocGroup("nodeGroup"))) ::
        //Menu(Loc("UploadedFiles", List("secure", "nodeManager","uploadedFiles"), <span>Manage uploaded files</span>, LocGroup("filesGroup"))) ::
      )

    def buildManagerMenu(name:String) =
      Menu(name+"ManagerHome", <span>{name.capitalize} Policy</span>) /
        "secure" / (name+"Manager") / "index" >> TestAccess ( ()
            => userIsAllowed(Read("configuration")) ) submenus (

          Menu(name+"RuleManagement", <span>Rules</span>) /
            "secure" / (name+"Manager") / "ruleManagement"
            >> LocGroup(name+"Group")
            >> TestAccess( () => userIsAllowed(Read("rule") ) )

        , Menu(name+"DirectiveManagement", <span>Directives</span>) /
            "secure" / (name+"Manager") / "directiveManagement"
            >> LocGroup(name+"Group")
            >> TestAccess( () => userIsAllowed(Read("directive") ) )

        , Menu("TechniqueLibraryManagement", <span>Techniques</span>) /
            "secure" / (name+"Manager") / "techniqueLibraryManagement"
            >> LocGroup(name+"Group")
            >> TestAccess( () => userIsAllowed(Read("technique") ) )
      )


    def administrationMenu =
      Menu("AdministrationHome", <span>Administration</span>) /
        "secure" / "administration" / "index" >> TestAccess ( ()
            => userIsAllowed(Read("administration")) ) submenus (

          Menu("archivesManagement", <span>Archives</span>) /
            "secure" / "administration" / "archiveManagement"
            >> LocGroup("administrationGroup")
            >> TestAccess ( () => userIsAllowed(Write("administration"),"/secure/administration/eventLogs") )

        , Menu("eventLogViewer", <span>Event Logs</span>) /
            "secure" / "administration" / "eventLogs"
            >> LocGroup("administrationGroup")

        , Menu("policyServerManagement", <span>Policy Server</span>) /
            "secure" / "administration" / "policyServerManagement"
            >> LocGroup("administrationGroup")
            >> TestAccess ( () => userIsAllowed(Write("administration"),"/secure/administration/eventLogs") )

        , Menu("pluginManagement", <span>Plugins</span>) /
            "secure" / "administration" / "pluginManagement"
            >> LocGroup("administrationGroup")
            >> TestAccess ( () => userIsAllowed(Write("administration"),"/secure/administration/eventLogs") )

        , Menu("databaseManagement", <span>Reports Database</span>) /
            "secure" / "administration" / "databaseManagement"
            >> LocGroup("administrationGroup")
            >> TestAccess ( () => userIsAllowed(Write("administration"),"/secure/administration/eventLogs") )
      )


    val rootMenu = List(
        Menu("Home", <span>Home</span>) / "secure" / "index"
      , Menu("Login") / "index" >> Hidden
      , nodeManagerMenu
      , buildManagerMenu("configuration")
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
      case eb:EmptyBox => ApplicationLogger.error("Error when trying to save the EventLog for application start")
      case _ => ApplicationLogger.info("Application Rudder started")
    }
  }

  private[this] def initPlugins(menus:List[Menu]) : List[Menu] = {

    //LiftSpringApplicationContext.springContext.refresh
    import scala.collection.JavaConversions._
    val pluginDefs = LiftSpringApplicationContext.springContext.getBeansOfType(classOf[RudderPluginDef]).values

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

  private[this] def userIsAllowed(requiredAuthz:AuthorizationType, redirection:String = "/secure/index") : Box[LiftResponse] =  {
    if(CurrentUser.checkRights(requiredAuthz)) {
      Empty
    } else {
      Full(RedirectWithState(redirection, RedirectState(() => (), "You are not authorized to access that page, please contact your administrator." -> NoticeType.Error ) ) )
    }
  }
}
