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

package com.normation.rudder.domain.policies

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.campaigns.CampaignId
import com.normation.rudder.tenants.HasSecurityTag
import com.normation.rudder.tenants.SecurityTag
import scala.xml.*

/*
 * Two way of modeling the couple (directiveId, rev) :
 * - either we do DirectiveId(uuid: String, rev: Rev).
 *   We tell that the revision is part of the directive identifier, and that an identifier
 *   always has a revision.
 *   It is likely the solution that will make simpler to never forget the migration from
 *   id = uuid to id = (uuid, rev). But:
 *   - it may complexify some automatic derivation, for ex for JSON (ie either we change json format for
 *     "directive": { "id": {"uuid":"xxxxx", "rev": "xxxxx" }, ... }
 *     which is breaking change for no good reason, especially if we want to make "rev" (or "rev") optionnal
 * - or we just add a "rev" (or "rev") field in directive. It doesn't break any existing serialisation API, it
 *   allows to continue to speak about "the directive ID" as just the uuid part, and have rev in addition.
 *   But it means that we will need to be extra-careful to not forget a place that for now use "id" and that will
 *   need to be duplicated or augmented with an optionnal "rev" parameter.
 *
 * I *think* we should change as little serialisation format as possible, especially in API, because they are the worst
 * breaking changes possible, and from an external observer, not providing rev should let everything work as before.
 * So we should make a hard constraint that JSON API will remain the same (with possibly an added "rev" field).
 *
 * Given that, we should (at least in the begining) try to minize distance between API serialisation format and internal
 * format. So go for the second option, and be careful when evolving methods.
 *
 * ==== some more evolution
 *
 * - forcing rev everywhere, when we want it to be almost always `DEFAULT_REV`, is not efficient. We should use
 *   Option[Rev]
 *   BUT it means that special attention need to be used on unserialisation: defaultValue (if serialised)
 *   must be unserialized to `None`.
 * - the code is crying for a DirectiveRId(id: DirectiveId, rev: Option[Rev] = None)
 *
 * ==== case of techniques
 *
 * Techniques already have a version as part of their ID. That version is different from our revision, as it is not strict.
 * But it seems that our revision is more a complement than a totally other concept:
 * - two techniques with a difference here must have different directories for files/etc
 * - directives with different version can't be merged/etc
 *
 *
 */

/*
 * directive unique identifier. Must be unique, so it's likely an UUID, but it could be
 * an ULID for ex https://wvlet.org/airframe/docs/airframe-ulid if we want to know about
 * time stamp & be a bit more space efficient.
 */
final case class DirectiveUid(value: String) extends AnyVal {
  def debugString: String = value
  def serialize:   String = value
}

/*
 * A directive identifier is composed of the directive unique identifier and the directive revision.
 * For backward compatibility, the UID field is named "id" in most serialized format. We will keep
 * "uid" in code to avoid code looking like `directive.id.id`.
 */
final case class DirectiveId(uid: DirectiveUid, rev: Revision = GitVersion.DEFAULT_REV) {
  def debugString: String = serialize

  def serialize: String = rev match {
    case GitVersion.DEFAULT_REV => uid.value
    case r                      => s"${uid.value}+${r.value}"
  }
}

object DirectiveId {

  // parse a directiveId which was serialize by "id.serialize"
  def parse(s: String): Either[String, DirectiveId] = {
    GitVersion.parseUidRev(s).map {
      case (id, rev) =>
        DirectiveId(DirectiveUid(id), rev)
    }
  }
}

/**
 * Define a directive.
 *
 * From a business point of view, a directive is a general
 * policy about your infrastructure, like "our password must be
 * 10 chars, mixed symbol, case, number".
 *
 * In Rudder, a Directive is derived from a technique on which
 * we are going to bind parameter to values matching the business
 * directive. For example, in our example, it could be
 * "Unix Password management with passwd"
 *
 * A directive also keep other information, like the priority
 * of that directive compared to other directive derived from the
 * the same technique.
 *
 */
//TODO: why not keeping techniqueName here ? data duplication ?

final case class Directive(
    // As of 7.0, an identifier is a couple of (object uuid, revision id).
    // see rational in comment above
    id: DirectiveId,

    /**
     * They reference one and only one Technique version
     */
    techniqueVersion: TechniqueVersion,

    /**
     * The list or parameters with their values.
     */
    parameters: Map[String, Seq[String]],

    /**
     * A human readable name for that directive,
     * typically used for CSV/grid header
     * i.e: "SEC-042 Debian Etch"
     * Can not be empty nor null.
     */
    name: String,

    /**
     * Short description, typically used as field description
     * Can not be empty nor null.
     */
    shortDescription: String,

    /**
     * Policy mode defined for that Directive
     * Three possibles values for now:
     * None => Default (use global mode)
     * Some => Verify or Enforce
     */
    policyMode: Option[PolicyMode],

    /**
     * A long, detailed description, typically used for
     * tooltip. It allows reach content.
     * Can be empty (and is by default).
     */
    longDescription: String = "",

    /**
     * For policies which allows only one configured instance at
     * a given time for a given node, priority allows to choose
     * the policy to deploy.
     * Higher priority is better, default is 5
     */
    priority: Int = 5,

    /**
     * Define if the policy is activated.
     * If it is not, configuration based on that policy should not be considered
     * for deployment on nodes.
     */
    _isEnabled: Boolean = false,
    isSystem:   Boolean = false,

    /**
     * Optionally, Directive can have Tags
     */
    tags:       Tags = Tags(Set()),
    // security so that in json is becomes: { "security": { "tenants": [...] }, ...}
    security:   Option[SecurityTag], // optional for backward compat. None means "no tenant"
    /**
    * Directive may have a schedule. A schedule generates event that
    * ask for the agent to run the or not the directive.
    * This is None by default, for compat with previous version of Rudder.
    */
    scheduleId: Option[CampaignId] = None
) {
  // system object must ALWAYS be ENABLED.
  def isEnabled: Boolean = _isEnabled || isSystem
}

object Directive {
  given HasSecurityTag[Directive] with {
    extension (a: Directive) {
      override def security: Option[SecurityTag] = a.security
      override def debugId:  String              = a.id.debugString
      override def updateSecurityContext(security: Option[SecurityTag]): Directive = a.copy(security = security)
    }
  }
}

final case class SectionVal(
    sections: Map[String, Seq[SectionVal]] = Map(), // name -> values

    variables: Map[String, String] = Map() // name -> values
)

object SectionVal {
  val ROOT_SECTION_NAME = "sections"

  def toOptionnalXml(sv: Option[SectionVal], sectionName: String = ROOT_SECTION_NAME): Node = {
    <section name={sectionName}>
      {
      sv.map(innerSectionXML(_)).getOrElse(NodeSeq.Empty)
    }
    </section>
  }

  // kept for compatibility with the plugin change-validation
  def toXml(sv: SectionVal, sectionName: String = ROOT_SECTION_NAME): Node = {
    <section name={sectionName}>
      {innerSectionXML(sv)}
    </section>
  }

  private def innerSectionXML(section: SectionVal):                                            NodeSeq    = {
    // variables
    section.variables.toSeq.sortBy(_._1).map {
      case (variable, value) =>
        <var name={variable}>{value}</var>
    } ++
    // section
    (for {
      (sectionName, sectionIterations) <- section.sections.toSeq.sortBy(_._1)
      sectionValue                     <- sectionIterations
    } yield {
      this.toXml(sectionValue, sectionName)
    })
  }
  def directiveValToSectionVal(rootSection: SectionSpec, allValues: Map[String, Seq[String]]): SectionVal = {
    /*
     * build variables with a parent section multivalued.
     */
    def buildMonoSectionWithMultivaluedParent(spec: SectionSpec, index: Int): SectionVal = {
      if (spec.isMultivalued) throw new RuntimeException("We found a multivalued subsection of a multivalued section: " + spec)

      // variable for that section: Map[String, String]
      val variables = spec.getDirectVariables.map { vspec =>
        // optionnal values not given in API are not defined here, add an empty value
        (
          vspec.name,
          try { allValues.getOrElse(vspec.name, Seq())(index) }
          catch {
            case _: IndexOutOfBoundsException => ""
          }
        )
      }.toMap

      /*
       * Get subsection. We can have several, all mono-valued
       */
      val subsections = spec.getDirectSections.map { sspec =>
        (sspec.name, Seq(buildMonoSectionWithMultivaluedParent(sspec, index)))
      }.toMap

      SectionVal(subsections, variables)

    }

    def buildMultiSectionWithoutMultiParent(spec: SectionSpec): Seq[SectionVal] = {
      if (!spec.isMultivalued)
        throw new RuntimeException("We found a monovalued section where a multivalued section was asked for: " + spec)

      // find the number of iteration for that multivalued section.
      // try with a direct variable, and if the section has no direct variable, with the first direct section with a variable
      val cardinal = {
        val name = spec.getDirectVariables.toList match {
          case v :: tail => v.name
          case _         => // look for the first section with a var
            spec.getDirectSections
              .find(s => s.getDirectVariables.nonEmpty)
              .map(s => s.getDirectVariables.head.name)
              .getOrElse("NO VARIABLE !!!") // used name should not be a key
        }
        allValues.get(name).map(_.size).getOrElse(0)
      }

      // find variable of that section
      val multiVariables: Seq[Map[String, String]] = {
        for {
          i <- 0 until cardinal
        } yield {

          spec.getDirectVariables.map { vspec =>
            // Default value for our variable, will be use is there is no value for this variable, empty string if no default value
            val defaultValue = vspec.constraint.default.getOrElse("")
            // get Value for our variable in our for the current section (i), use default value if missing
            val value        = allValues.get(vspec.name).map(_(i)).getOrElse(defaultValue)
            (vspec.name, value)
          }.toMap
        }
      }

      // build subsections:
      val multiSections: Seq[Map[String, SectionVal]] = {
        for {
          i <- 0 until cardinal
        } yield {
          spec.getDirectSections.map(sspec => (sspec.name, buildMonoSectionWithMultivaluedParent(sspec, i))).toMap
        }
      }

      for {
        i <- 0 until cardinal
      } yield {
        // here, children section must be with a cardinal of 1 (monovalued)
        val sections = multiSections(i).map { case (k, s) => (k, Seq(s)) }.toMap
        SectionVal(sections, multiVariables(i))
      }
    }

    def buildMonoSectionWithoutMultivaluedParent(spec: SectionSpec): SectionVal = {
      val variables = spec.getDirectVariables.map { vspec =>
        // we can have an empty value for a variable, for non-mandatory ones
        (vspec.name, allValues.getOrElse(vspec.name, Seq(""))(0))
      }.toMap

      val sections = spec.getDirectSections.map { vspec =>
        if (vspec.isMultivalued) {
          (vspec.name, buildMultiSectionWithoutMultiParent(vspec))
        } else {
          (vspec.name, Seq(buildMonoSectionWithoutMultivaluedParent(vspec)))
        }
      }.toMap

      SectionVal(sections, variables)
    }

    buildMonoSectionWithoutMultivaluedParent(rootSection)
  }

  def toMapVariables(sv: SectionVal): Map[String, Seq[String]] = {
    import scala.collection.mutable.{Map, Buffer}
    val res = Map[String, Buffer[String]]()

    def recToMap(sec: SectionVal): Unit = {
      sec.variables.foreach {
        case (name, value) =>
          res.getOrElseUpdate(name, Buffer()).append(value)
      }
      sec.sections.foreach {
        case (_, sections) =>
          sections.foreach(recToMap(_))
      }
    }

    recToMap(sv)
    res.map { case (k, buf) => (k, buf.toSeq) }.toMap
  }
}
