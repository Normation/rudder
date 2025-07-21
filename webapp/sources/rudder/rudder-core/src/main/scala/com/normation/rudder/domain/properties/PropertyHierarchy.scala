package com.normation.rudder.domain.properties

import cats.Monoid
import cats.Semigroup
import cats.data.Ior
import cats.syntax.semigroup.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.properties.ParentProperty.VertexParentProperty
import com.normation.rudder.properties.GroupProp
import com.typesafe.config.ConfigRenderOptions
import enumeratum.Enum
import enumeratum.EnumEntry
import org.apache.commons.text.StringEscapeUtils
import zio.*
import zio.json.*

/*
 * Kind of properties in the hierarchy
 */
sealed trait ParentPropertyKind(override val entryName: String) extends EnumEntry
object ParentPropertyKind                                       extends Enum[ParentPropertyKind] {
  case object Node   extends ParentPropertyKind("node")
  case object Group  extends ParentPropertyKind("group")
  case object Global extends ParentPropertyKind("global")

  override def values: IndexedSeq[ParentPropertyKind] = findValues

  implicit val codecParentPropertyKind: JsonCodec[ParentPropertyKind] =
    JsonCodec.string.transformOrFail(ParentPropertyKind.withNameInsensitiveEither(_).left.map(_.getMessage), _.entryName)
}

/*
 * A property with its inheritance context as a parent of some other property:
 * - key / provider
 * - resulting value on node
 * - list of diff:
 *   - name of the diff provider: group/target name, global parameter
 */
sealed trait ParentProperty[P <: GenericProperty[?]] {
  def kind:          ParentPropertyKind
  // def displayName: String // human-readable information about the parent providing prop
  def id:            String
  def name:          String
  def value:         GenericProperty[P]
  def resolvedValue: GenericProperty[P]
}

object ParentProperty {

  // node is a leaf of a hierarchy - which can be confusing. Keeping node/group/global naming scheme semantic here.
  final case class Node(
      override val name:  String,
      nodeId:             NodeId,
      override val value: NodeProperty,
      parentProperty:     Option[VertexParentProperty[?]]
  ) extends ParentProperty[NodeProperty] {
    override val kind:          ParentPropertyKind            = ParentPropertyKind.Node
    override def id:            String                        = nodeId.value
    override val resolvedValue: GenericProperty[NodeProperty] = parentProperty match {
      case None    => value
      case Some(v) => NodeProperty(GenericProperty.mergeConfig(v.resolvedValue.config, value.config)(v.resolvedValue.inheritMode))
    }

  }

  // A node and group can't have a node parent property, only group or global.
  // Not using "node" (as in "not a leaf of the tree") but vertex, to avoid confusion.
  sealed trait VertexParentProperty[P <: GenericProperty[?]] extends ParentProperty[P]

  // a group can have groups or global parents
  final case class Group(
      override val name:  String,
      groupId:            NodeGroupId,
      override val value: GroupProperty,
      parentProperty:     Option[VertexParentProperty[?]]
  ) extends VertexParentProperty[GroupProperty] {
    override val kind:          ParentPropertyKind             = ParentPropertyKind.Group
    override def id:            String                         = groupId.serialize
    override val resolvedValue: GenericProperty[GroupProperty] = parentProperty match {
      case None    => value
      case Some(v) =>
        GroupProperty(GenericProperty.mergeConfig(v.resolvedValue.config, value.config)(v.resolvedValue.inheritMode))
    }
  }

  // a global parameter has the same name as property so no need to be specific for name
  final case class Global(override val value: GlobalParameter) extends VertexParentProperty[GlobalParameter] {
    override val name:          String                           = value.name
    override def id:            String                           = value.name
    override val kind:          ParentPropertyKind               = ParentPropertyKind.Global
    override val resolvedValue: GenericProperty[GlobalParameter] = value
  }
}

/**
 * A node property with its inheritance/overriding context.
 */

sealed trait PropertyHierarchy {
  def hierarchy: ParentProperty[?]
  def prop:      GenericProperty[?]
}

case class NodePropertyHierarchy(id: NodeId, override val hierarchy: ParentProperty[?])             extends PropertyHierarchy {
  override lazy val prop: GenericProperty[?] = hierarchy match {
    case n: ParentProperty.Node =>
      val prop = NodeProperty(hierarchy.resolvedValue.config)
      if (n.parentProperty.isEmpty) prop else prop.withProvider(GroupProp.OVERRIDE_PROVIDER)
    case _ =>
      NodeProperty(hierarchy.resolvedValue.config)
        .withProvider(GroupProp.INHERITANCE_PROVIDER)
        .withVisibility(hierarchy.resolvedValue.visibility)
  }
}
case class GroupPropertyHierarchy(id: NodeGroupId, override val hierarchy: VertexParentProperty[?]) extends PropertyHierarchy {
  override lazy val prop: GenericProperty[?] = hierarchy match {
    case g: ParentProperty.Group if g.groupId == id =>
      val prop = GroupProperty(hierarchy.resolvedValue.config)
      if (g.parentProperty.isEmpty) prop else prop.withProvider(GroupProp.OVERRIDE_PROVIDER)
    case _ =>
      GroupProperty(hierarchy.resolvedValue.config)
        .withProvider(GroupProp.INHERITANCE_PROVIDER)
        .withVisibility(hierarchy.resolvedValue.visibility)
  }
}

/**
 * Error ADT that consists of specific errors on a property, or a
 */
sealed trait PropertyHierarchyError {
  def message: String

  def debugString: String
}

/**
 * Error that are specific to some properties.
 */
sealed trait PropertyHierarchySpecificError extends PropertyHierarchyError {

  /**
   * Properties whose name is the key of the map, are associated with
   * a hierarchy of inherited properties from parents that lead to the error,
   * and an error message
   */
  def propertiesErrors: Map[String, (GenericProperty[?], NonEmptyChunk[ParentProperty[?]], String)]
}

object PropertyHierarchyError {
  case class MissingParentGroup(groupId: NodeGroupId, groupName: String, parentGroupId: String) extends PropertyHierarchyError {
    override def message: String =
      s"parent group with id '${parentGroupId}' is missing for group '${groupName}'(${groupId.serialize})"

    override def debugString: String =
      s"MissingParentGroup(groupId=${groupId.serialize},groupName=${groupName},parentGroupId=${parentGroupId})"
  }

  object MissingParentGroup {
    def apply(groupProp: GroupProp, parentGroupId: String): MissingParentGroup =
      MissingParentGroup(groupProp.groupId, groupProp.groupName, parentGroupId)
  }

  // The message can be very broad or specific here, it is known from the DAG resolution
  case class DAGHierarchyError(override val message: String) extends PropertyHierarchyError {
    override def debugString: String = "DAGError"
  }

  // There are conflicts by node property
  case class PropertyHierarchySpecificInheritanceConflicts(
      conflicts: Map[GenericProperty[?], NonEmptyChunk[VertexParentProperty[?]]]
  ) extends PropertyHierarchySpecificError {
    override def toString(): String = debugString

    override def message: String = {
      conflicts.toList.map {
        case (k, hierarchies) =>
          val error = conflictMessage(k, hierarchies)
          s"In hierarchy with ${hierarchies.map(_.id).mkString(", ").mkString("{", "|", "}")} :\n${error}"
      }.mkString("\n")
    }

    override def debugString: String = {
      s"""PropertyInheritanceConflicts([${conflicts.map {
          case (prop, c) =>
            prop.name + "->" + c
              .map(p => s"${p.kind} ${p.name}")
              .mkString("{", "|", "}") // H = Group G1 (g1-uuid), Group G2 (g2-uuid)
        }.mkString(",")}])"""
    }

    def ++(that: PropertyHierarchySpecificInheritanceConflicts): PropertyHierarchySpecificInheritanceConflicts = {
      PropertyHierarchySpecificInheritanceConflicts(conflicts ++ that.conflicts)
    }

    /**
     * This method is useful when resolving conflicts
     */
    def resolve(propNames: Set[String]): PropertyHierarchySpecificInheritanceConflicts = {
      copy(conflicts = conflicts.filterNot { case (k, _) => propNames.contains(k.name) })
    }

    // the map needs to be evaluated eagerly to get all property errors at once and atomic checks
    override val propertiesErrors: Map[String, (GenericProperty[?], NonEmptyChunk[ParentProperty[?]], String)] = {
      conflicts.map {
        case (k, props) =>
          (
            k.name,
            (k, props, conflictMessage(k, props))
          )
      }
    }

    private def conflictMessage(prop: GenericProperty[?], conflicts: NonEmptyChunk[ParentProperty[?]]): String = {
      def inheritanceName(p: ParentProperty[?]): String = {
        p match {
          case n: ParentProperty.Node =>
            n.parentProperty match {
              case None         => n.name
              case Some(parent) => s"${n.name} <- ${inheritanceName(parent)} "
            }
          case _ => ""
        }
      }

      val faulty = conflicts.map(inheritanceName)

      s"Error when trying to find overrides for group property '${prop.name}'. " +
      s"Several groups which are not in an inheritance relation define it. You will need to define " +
      s"a new group with all these groups as parent and choose the order on which you want to do " +
      s"the override by hand. Faulty groups:\n ${faulty.mkString("\n ")}"

    }
  }

  object PropertyHierarchySpecificInheritanceConflicts {

    /**
     * Represent a single failure from a property and the conflicting inheritance lines
     */
    def one(
        prop:    GenericProperty[?],
        parents: NonEmptyChunk[VertexParentProperty[?]]
    ): PropertyHierarchySpecificInheritanceConflicts = {
      PropertyHierarchySpecificInheritanceConflicts(
        Map(prop -> parents)
      )
    }

    val empty: PropertyHierarchySpecificInheritanceConflicts = PropertyHierarchySpecificInheritanceConflicts(Map.empty)

    // monoid is used when using combinators from the Ior datatype to accumulate conflicts
    implicit val monoid: Monoid[PropertyHierarchySpecificInheritanceConflicts] = Monoid.instance(empty, _ ++ _)
  }

  /**
   * Instance of semigroup that result from the hierarchy of node property errors :
   * non specific errors are more important that specific ones.
   * The instance is used when using combinators from the Ior datatype
   * to accumulate conflicts or choose between errors.
   *
   * From the ADT structure and the implementation of this instance,
   * we assume that specific errors are discarded.
   * To keep all errors, consider using a collection of errors and remove this instance.
   */
  implicit val semigroup: Semigroup[PropertyHierarchyError] = {
    Semigroup.instance {
      case (a: PropertyHierarchySpecificError, b: PropertyHierarchySpecificError) => a |+| b // accumulate specific errors
      case (g: PropertyHierarchyError, _: PropertyHierarchySpecificError)         => g       // choose the least specific
      case (_: PropertyHierarchyError, snd: PropertyHierarchyError)               => snd     // the latest error (maybe we want a CombinedError)
    }

  }
}

object PropertyHierarchySpecificError {

  import PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts

  implicit val semigroup: Semigroup[PropertyHierarchySpecificError] = Semigroup.instance {
    case (a: PropertyHierarchySpecificInheritanceConflicts, b: PropertyHierarchySpecificInheritanceConflicts) =>
      a |+| b // accumulate conflicts
  }
}

/**
 * The status of resolution of the hierarchy of node properties :
 * the node properties hierarchy can sometimes be evaluated
 * in a broken global context (e.g. groups structure is non resoluble)
 * , or there can be failures to resolve some specific properties only.
 *
 * There is also a success case when all properties are successfully resolved,
 * but the API assumes that there are resolved properties (even an empty set).
 *
 * The API is very similar to a Ior one and in fact that datatype is used
 * for the combinators it provides, and this type is just the domain
 * definition that prevents the Ior datatype to be visible at many places.
 */
sealed abstract class ResolvedNodePropertyHierarchy(val resolved: Chunk[PropertyHierarchy]) {
  def prependFailureMessage(msg: String): ResolvedNodePropertyHierarchy

  def noResolved: Boolean = resolved.isEmpty
}

/**
 * The failure that has some additional context message for when
 * there is a global context for the node property error.
 *
 * It wraps the error to also serve as a translation agains the
 * case of having both success and error,
 * and is somehow associated with the Ior datatype
 */
case class FailedNodePropertyHierarchy private (
    success:        Chunk[PropertyHierarchy],
    error:          PropertyHierarchyError,
    contextMessage: String
) extends ResolvedNodePropertyHierarchy(success) {
  // context message already has newline when prepended
  def getMessage: String = (if (contextMessage.nonEmpty) s"${contextMessage}${error.message}" else error.message).strip

  override def prependFailureMessage(msg: String): ResolvedNodePropertyHierarchy =
    copy(contextMessage = msg ++ "\n" ++ contextMessage)
}

object FailedNodePropertyHierarchy {

  /**
   * Constuctor needs to validate that properties in success does not intersect specific node property errors
   */
  def apply(
      it:             Iterable[PropertyHierarchy],
      error:          PropertyHierarchyError,
      contextMessage: String = ""
  ): FailedNodePropertyHierarchy = {
    val success = error match {
      case propErrors: PropertyHierarchySpecificError =>
        it.filterNot(p => propErrors.propertiesErrors.keySet.contains(p.prop.name))
      case _ => Chunk.from(it)
    }
    new FailedNodePropertyHierarchy(Chunk.from(success), error, contextMessage)

  }
}

/**
 * Just a list of resolved properties (we know it's a non empty one
 * and model it like that for the constraint of instantiation)
 */
case class SuccessNodePropertyHierarchy(override val resolved: Chunk[PropertyHierarchy])
    extends ResolvedNodePropertyHierarchy(resolved) {
  override def prependFailureMessage(msg: String): ResolvedNodePropertyHierarchy = this
}

object ResolvedNodePropertyHierarchy {

  def empty: SuccessNodePropertyHierarchy = SuccessNodePropertyHierarchy(Chunk.empty)

  /**
   * Main use case is to translate from Ior, and specifically an empty success with no error is an empty result
   */
  def from(ior: Ior[PropertyHierarchyError, Iterable[PropertyHierarchy]]): ResolvedNodePropertyHierarchy = {
    ior match {
      case Ior.Left(err)     =>
        FailedNodePropertyHierarchy(Chunk.empty, err)
      case Ior.Both(err, it) =>
        FailedNodePropertyHierarchy(it, err)
      case Ior.Right(it)     =>
        SuccessNodePropertyHierarchy(Chunk.from(it))
    }
  }

  implicit class ResolvedNodePropertyHierarchyOptionOps(opt: Option[ResolvedNodePropertyHierarchy]) {
    def orEmpty: ResolvedNodePropertyHierarchy = opt match {
      case None        => empty
      case Some(value) => value
    }
  }

}

/**
 * The part dealing with JsonSerialisation of node related
 * attributes (especially properties) and parameters
 */
object JsonPropertyHierarchySerialisation {

  import net.liftweb.json.*
  import net.liftweb.json.JsonDSL.*

  implicit class ParentPropertyToJSon(val p: ParentProperty[?]) extends AnyVal {
    def toJson: JValue = {
      p match {
        case ParentProperty.Global(value) =>
          (
            ("kind"            -> p.kind.entryName)
            ~ ("name"          -> p.name)
            ~ ("value"         -> GenericProperty.toJsonValue(p.value.value))
            ~ ("resolvedValue" -> GenericProperty.toJsonValue(p.resolvedValue.value))
          )

        case _ =>
          (
            ("kind"            -> p.kind.entryName)
            ~ ("name"          -> p.name)
            ~ ("id"            -> p.id)
            ~ ("value"         -> GenericProperty.toJsonValue(p.value.value))
            ~ ("resolvedValue" -> GenericProperty.toJsonValue(p.resolvedValue.value))
          )
      }
    }
  }

  implicit class JsonNodePropertyHierarchy(val prop: PropertyHierarchy) extends AnyVal {
    implicit def formats: DefaultFormats.type = DefaultFormats

    private def buildHierarchy(displayParents: ParentProperty[?] => JValue): JObject = {

      prop.prop.toJsonObj ~ ("hierarchy" -> displayParents(prop.hierarchy)) ~ ("origval" -> GenericProperty.toJsonValue(
        prop.hierarchy.value.value
      ))

    }

    def toApiJson: JObject = {
      buildHierarchy(list => list.toJson)
    }

    def toApiJsonRenderParents: JObject = {
      def displayElem(p: ParentProperty[?]): String = {
        (p match {
          case _: ParentProperty.Global => None
          case n: ParentProperty.Node   => n.parentProperty.map(displayElem)
          case g: ParentProperty.Group  => g.parentProperty.map(displayElem)
        }).getOrElse("") +
        s"<p>from ${p.kind match {
            case ParentPropertyKind.Global => p.kind.entryName + " property"
            case _                         => p.kind.entryName
          }} <b>${p.name}${if (p.id.isEmpty) {
            ""
          } else s" (${p.id})"}</b>:<pre>${StringEscapeUtils
            .escapeHtml4(p.value.value.render(ConfigRenderOptions.defaults().setOriginComments(false)))}</pre></p>"
      }

      buildHierarchy(displayElem.andThen(JString))
    }

  }

  implicit class JsonNodePropertiesHierarchy(val props: List[PropertyHierarchy]) extends AnyVal {
    implicit def formats: DefaultFormats.type = DefaultFormats

    def toApiJson: JArray = {
      JArray(props.sortBy(_.prop.name).map(p => p.toApiJson))
    }

    def toApiJsonRenderParents: JArray = {
      JArray(props.sortBy(_.prop.name).map(p => p.toApiJsonRenderParents))
    }

  }
}
