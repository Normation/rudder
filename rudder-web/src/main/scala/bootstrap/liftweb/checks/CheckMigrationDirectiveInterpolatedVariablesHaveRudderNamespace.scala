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
package checks

import net.liftweb.common._
import org.slf4j.LoggerFactory
import net.liftweb.common.Logger
import net.liftweb.common.Failure
import com.normation.rudder.repository.DirectiveRepository
import com.normation.rudder.domain.logger.MigrationLogger
import com.normation.rudder.domain.policies.Directive
import com.normation.utils.Control.sequence
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.eventlog._

/**
 * See http://www.rudder-project.org/redmine/issues/3152
 * 
 * That class checks that interpolated variable in Directive variable
 * (variable with syntax: ${some_var} ) are correctly using the "rudder"
 * namespace, so that that doesn't clash with CFEngine interpolation. 
 * 
 * The problem is that an user may have used its own interpolated 
 * variable, and so we don't have an exaustive list of directive
 * to change.
 * 
 * The check is idempotent, or almost - we have a non-atomic datastore
 * (LDAP), and so we adopt that strategy:
 * - check if system directive are migrated (i.e, their intepolated 
 *   variables use ${rudder.VAR}
 * - if the migration is done, end.
 * - else, get all directives with interpolated variable
 *   - migrate all variable non starting with ${rudder.]
 *     (here, we do make the assumption that the user never used 
 *     cfengine variables in directive values
 *   - save all non-system variables
 *   - save system variable.
 * 
 * Hence, by terminating with system variable, we are assured that
 * we don't miss any user defined variable.
 * 
 */
class CheckMigrationDirectiveInterpolatedVariablesHaveRudderNamespace(
    repos  : DirectiveRepository
  , uuidGen: StringUuidGenerator
) extends BootstrapChecks {
  

  private[this] object logger extends Logger {
    override protected def _logger = LoggerFactory.getLogger("migration")
    val defaultErrorLogger : Failure => Unit = { f =>
      _logger.error(f.messageChain)
      f.rootExceptionCause.foreach { ex =>
        _logger.error("Root exception was:", ex)
      }
    }
  }

  private[this] val variableRegex = """\$\{(.*)\}""".r
  
  override def checks() : Unit = {
      
    repos.getAll(includeSystem = true) match {
      case eb:EmptyBox =>
        val f = (eb ?~! "Can not check that Rudder interpolated variable in directive variables use 'rudder' namespace")
        logger.defaultErrorLogger(f)
      
      case Full(directives) =>
      
        val (systemDirectives, userDirectives) = directives.partition(d => d.isSystem)

        if(systemDirectives.exists { d => migrateDirectiveParametersToRudderNamespace(d).isDefined}) {
          //migrate everything
          val newUserDirectives = migrateDirectives(userDirectives)
          val newSystemDirectives = migrateDirectives(systemDirectives)
          
          //generate a unique modification ID for the whole migration process
          val modId = ModificationId(uuidGen.newUuid)
          
          logger.info("Starting migration of inline variables in Directives (i.e variables with ${XXX} syntax) to new 'rudder' namespace (i.e to syntax ${rudder.XXX})")
          (sequence(newUserDirectives ++ newSystemDirectives) { directive =>
            val message = "Migrating inline variables in Directive %s (uuid: %s) so that they use the new 'rudder' namespace".format(directive.name, directive.id.value)
            logger.info(message)
            for {
              activeTechnique <- repos.getActiveTechnique(directive.id)
              saved           <- repos.saveDirective(activeTechnique.id, directive, modId, RudderEventActor, Some(message))
            } yield {
              saved
            }
          }) match {
            case eb: EmptyBox =>
              val f = (eb ?~! "Can not finish the migration process due to an error")
              logger.defaultErrorLogger(f)
            case Full(res) => 
              logger.info("Migration of inline variables in Directives to new 'rudder' namespace succeeded")

          }
          
          
        } else {
          //OK, migration done
          logger.info("Migration of inline variables in Directives to 'rudder' namespace already done, skipping")
        }
      
    }
  }
  
  /**
   * Migrate a list of directive. Return only directives that actually need
   * to be migrated. 
   */
  private[this] def migrateDirectives(directives:Seq[Directive]) : Seq[Directive] = {
    directives.flatMap { case d => 
      migrateDirectiveParametersToRudderNamespace(d).map { params => d.copy(parameters = params) }
    }
  }
  
  /**
   * Find all directive parameters with interpolated variable that does not
   * already use the rudder namespace. 
   * An empty map may say that all variable are already using the namespace, 
   * or that no interpolated variable were used in that Directive.
   * 
   * Return the map of parameter migrated, or nothing if the directive does not
   * contain any variable to migrate.
   */
  private[this] def migrateDirectiveParametersToRudderNamespace(directive:Directive) : Option[Map[String, Seq[String]]] = {
    //try to migrate, create the resulting map of params with value => 
    // a list of (key, (param, wasMigrated)) where
    //is migrated say that the we had to migrate a variable.
    val migrated : Map[String, (Seq[String],Boolean)]= directive.parameters.map { case (key, params) =>
      val newParams = for {
        param <- params
      } yield {
        useANonRudderNamespace(param) match {
          case Some(notMigratedParamName) =>
            ("${rudder." + notMigratedParamName + "}" , true)
          case None => (param,false)
        }
      }
      //the param is migrated if any of its value was migrated
      val wasMigrated = (false /: newParams){ case (paste, (_,current)) => paste || current }
      
      (key,(newParams.map(_._1),wasMigrated))
    }
    
    if(migrated.exists { case (_, (_,wasMigrated)) => wasMigrated }) {
      Some(migrated.map { case (k, (params, _) ) => (k, params) } )
    } else {
      None
    }    
  }
  
  /**
   * Return the name of the variable that is NOT in Rudder 
   * namespace, or nothing in case there is :
   * - it's not a variable
   * - it's a variable already migrated to rudder
   * 
   * NOTICE than we can ONLY have one parameter, alone, and so we can not have
   * cases with """${foo.bar} ${rudder.plop}""" - that's an all or nothing choice.
   */
  private[this] def useANonRudderNamespace(s:String) : Option[String] = {
    s.toLowerCase match {
      case variableRegex(x) if(!x.startsWith("rudder.")) => Some(x)
      case _ => None
    }
  }  
}

