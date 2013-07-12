package com.normation.rudder.migration

import scala.xml.Elem
import net.liftweb.common._


/**
 * A service able to migrate raw XML eventLog
 * of entity (rules, groups, directives)
 * up to the current file format.
 *
 * We only support these elements:
 * - directive related:
 *   - activeTechniqueCategory (policyLibraryCategory)
 *   - activeTechnique  (policyLibraryTemplate)
 *   - directive  (policyInstance)
 * - rules related:
 *   - rule (configurationRule)
 * - groups related:
 *   - nodeGroupCategory
 *   - nodeGroup
 */
trait XmlEntityMigration {

  def getUpToDateXml(entity:Elem) : Box[Elem]

}


/**
 * Implementation
 */
class DefaultXmlEventLogMigration(
    xmlMigration_2_3: XmlMigration_2_3
) extends XmlEntityMigration {

  def getUpToDateXml(entity:Elem) : Box[Elem] = {

    for {
      versionT <- Box(entity.attribute("fileFormat").map( _.text )) ?~! "Can not migrate element with unknow fileFormat: %s".format(entity)
      version  <- try { Full(versionT.toFloat.toInt) } catch { case e:Exception => Failure("Bad version (expecting an integer or a float: '%s'".format(versionT))}
      migrate  <- version match {
                    case 2 => migrate2_3(entity)
                    case 3 => Full(entity)
                    case x => Failure("Can not migrate XML file with fileFormat='%s' (expecting 1,2 or 3)".format(version))
                  }
    } yield {
      migrate
    }
  }

  private[this] def migrate2_3(xml:Elem) : Box[Elem] = {
    xml.label match {
      case "rule" => xmlMigration_2_3.rule(xml)
      case _ => xmlMigration_2_3.other(xml)
    }
  }

}


