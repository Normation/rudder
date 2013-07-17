/*
*************************************************************************************
* Copyright 2013 Normation SAS
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
package bootstrap.liftweb.checks

import org.slf4j.LoggerFactory
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.utils.Control._
import bootstrap.liftweb.BootstrapChecks
import net.liftweb.common._
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository

/**
 * See http://www.rudder-project.org/redmine/issues/3642
 *
 * We fixed the regex on variable check name in Generic CFEngine Variable Definition
 * but previously, user could use "." in the variable name, which is now forbiden
 * This check check if there are any variables name in directives based on this technique
 * with a dot in it, if so, search in all directives for the specific variable
 * name to replace . by _, then replace in the directive based on generic cfengine variable definition
 */
class CheckDotInGenericCFEngineVariableDef (
    roRepos: RoDirectiveRepository
  , woRepos: WoDirectiveRepository
  , uuid   : StringUuidGenerator
) extends BootstrapChecks {

  private[this] val logger = new Logger {
    override protected def _logger = LoggerFactory.getLogger("migration")
    val defaultErrorLogger : Failure => Unit = { f =>
      _logger.error(f.messageChain)
      f.rootExceptionCause.foreach { ex =>
        _logger.error("Root exception was:", ex)
      }
    }
  }

  private[this] val dotRegex = """(.*)\.(.*)""".r
  private[this] val parameterName = "GENERIC_VARIABLE_NAME"
  private[this] val replacementChar = "_"
  private[this] val genericCFEngineVarDef = "Generic CFEngine variable definition"

  override def checks() : Unit = {
    roRepos.getAll(includeSystem = false) match {
      case eb:EmptyBox =>
        val f = (eb ?~! "Can not check if there are variable name with a dot in them in the directives based on genericCFEngineVariableDefinition")
        logger.defaultErrorLogger(f)

      case Full(directives) =>
        getVariablesWithDot(directives) match {
          case eb:EmptyBox =>
            val f = (eb ?~! "Can not check if there are variable name with a dot in them in the directives based on genericCFEngineVariableDefinition")
            logger.defaultErrorLogger(f)
          case Full(matchedDefinition) if( matchedDefinition.size == 0 ) =>
            logger.info("No variable name with dot defined in the generic CFEngine Variable Definition, skipping")

          case Full(matchedDefinition) =>
            logger.info("Starting migration of variable name with dot defined in the generic CFEngine Variable Definition")
            // We iterate over all directives, over all values
            val flatten = matchedDefinition.flatMap { case (directive, keys) =>
                keys.map(key => (directive, key))
            }
            sequence(flatten) { case (directive, value) => migrateVariableWithDot(directive, value)} match {
              case eb:EmptyBox =>
                val f = (eb ?~! "An error occured during the undotting of variable names in generic CFEngine Variable Definition completed with success")
                logger.defaultErrorLogger(f)
              case Full(result) =>
                logger.info("Migration of all variable names in generic CFEngine Variable Definition completed with success")
            }
        }
    }
  }

  /**
   * looks in given directives (based on generic cfengine conf) for variable name with a dot
   * returns directive, and the variables within with a dot
   */
  private[this] def getVariablesWithDot(directives:Seq[Directive]) : Box[Seq[(Directive, Seq[String])]] = {
    sequence(directives) { directive => roRepos.getDirectiveWithContext(directive.id) } .map { directivesWithContext =>
        // first, look for all possible variable with a dot in it
        for {
           (technique, activeTechnique, directive) <- directivesWithContext
           if( technique.name == genericCFEngineVarDef )
           (key, values) <- directive.parameters
                              .filter { case (key,values) => (key == parameterName) }
                              .map { case (key,values) => (key , (values.filter(_.contains(".")))) }
           if( values.size > 0 )
        } yield {
          (directive, values)
        }
    }
  }

  private[this] def newModId() = ModificationId(uuid.newUuid)

  /**
   * create a regexp for matching the value, anywhere in the content
   * with multi line support
   */
  private[this] def buildVariableUsageRegex(variableValue:String) : String = {
    """(?s).*\$[\{\(]generic_variable_definition."""+variableValue+"""[\}\)].*"""
  }

  private[this] def buildVariableReplacement(variableValue:String) : (String, String) = {
    ("""generic_variable_definition."""+variableValue, """generic_variable_definition."""+variableValue.replace(".", replacementChar))
  }

  /**
   * From a directive (generic cfengine variable def) and a variable value, (that contains a dot)
   * replace within this directive the dot by _
   * looks in all directives for values that matches the variable value
   * Replace in the directive using it
   * Then replace in the generic cfengine variable definition
   */
  private[this] def migrateVariableWithDot(refDirective: Directive, variableValue:String) : Box[DirectiveId] = {
    val regex = buildVariableUsageRegex(variableValue)
    // first, we fetch all directives
    for {
      directives        <- roRepos.getAll(false)
      matchedDirectives =  directives.filter { directive =>
                             directive.parameters.values.flatten.exists( value => value.matches(regex))
                           }
      result            <- sequence(matchedDirectives) { directive =>
                             val message = "Replacing dot in the variable %s with an underscore in directive %s (uuid: %s)".format(variableValue, directive.name, directive.id.value)
                             logger.info(message)
                             for {
                               activeTechnique   <- roRepos.getActiveTechnique(directive.id)
                               replacedDirective =  replaceInDirective(directive, variableValue)
                               saved             <- woRepos.saveDirective(activeTechnique.id, replacedDirective, newModId, RudderEventActor, Some(message))
                             } yield {
                               saved
                             }
                           }
      message           =  "Replacing dot in the variable %s with an underscore in %s (uuid: %s)".format(variableValue, refDirective.name, refDirective.id.value)
      log               =  logger.info(message)
      updatedDirective  <- for {
                             // I need to fetch each time the reference directive, as it may have several value to undot
                             (_,activeTechnique, directive) <- roRepos.getDirectiveWithContext(refDirective.id)
                             replacedDirective              =  replaceInGenericVariableDefDirective(directive, variableValue)
                             saved                          <- woRepos.saveDirective(activeTechnique.id, replacedDirective, newModId, RudderEventActor, Some(message))
                           } yield {
                             saved
                           }
    } yield {
      refDirective.id
    }
  }

  /**
   * Replace in a directive, the variable content by its value
   */
  private[this] def replaceInDirective(directive : Directive, variableValue:String) : Directive = {
    val (value, replacement) = buildVariableReplacement(variableValue)
    directive.copy(
      parameters = directive.parameters.map { case (key,values) =>
          (key, values.map(x => x.replace(value, replacement)))
      }
    )
  }

  /**
   * Replace in a generic variable definition, the variable content by its value
   */
  private[this] def replaceInGenericVariableDefDirective(directive : Directive, variableValue:String) : Directive = {
    directive.copy(
      parameters = directive.parameters.map { case (key,values) =>
        if (key == parameterName) {
          (key, values.map(x => x.replace(variableValue, variableValue.replace(".",replacementChar))))
        } else {
          (key, values)
        }
      }
    )
  }
}