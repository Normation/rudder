package com.normation.rudder.domain.properties

import cats.Monoid
import cats.Semigroup
import cats.data.Ior
import cats.syntax.semigroup.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.properties.GenericProperty.StringToConfigValue
import com.normation.rudder.domain.properties.PropertyVertex.ParentProperty
import com.normation.rudder.properties.GroupProp
import com.typesafe.config
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueFactory
import enumeratum.Enum
import enumeratum.EnumEntry
import org.apache.commons.text.StringEscapeUtils
import zio.*
import zio.json.*

/*
 * Kind of properties in the hierarchy
 */
sealed trait PropertyVertexKind(override val entryName: String) extends EnumEntry
object PropertyVertexKind                                       extends Enum[PropertyVertexKind] {
  case object Node   extends PropertyVertexKind("node")
  case object Group  extends PropertyVertexKind("group")
  case object Global extends PropertyVertexKind("global")

  override def values: IndexedSeq[PropertyVertexKind] = findValues

  implicit val codecParentPropertyKind: JsonCodec[PropertyVertexKind] =
    JsonCodec.string.transformOrFail(PropertyVertexKind.withNameInsensitiveEither(_).left.map(_.getMessage), _.entryName)
}

// resolved value must be a def so that it's actually actualized if the hierarchy change
sealed trait PropertyValueKind[P <: GenericProperty[?]](val resolvedValue: P)

object PropertyValueKind {
  // the case where the value is only defined in the item and there is no parent
  case class SelfValue[P <: GenericProperty[?]](value: P) extends PropertyValueKind[P](value)

  // the case where the value is purely inherited (there is no self value)
  case class Inherited[P <: GenericProperty[?]](parentProperty: ParentProperty[?])(builder: com.typesafe.config.Config => P)
      extends PropertyValueKind[P](
        builder(parentProperty.value.resolvedValue.config)
          .withProvider(GroupProp.INHERITANCE_PROVIDER)
          .asInstanceOf[P] // humpf
      )

  // the case where the value is defined both in the item and in its parent
  case class Overridden[P <: GenericProperty[?]](value: P, parentProperty: ParentProperty[?])
      extends PropertyValueKind[P](buildOverride(value, parentProperty)) {
    assert(
      value.name == parentProperty.value.resolvedValue.name,
      s"You can only create an overridden PropertyValueKind for the same property name. Here, child: '${value.name}' and parent: '${parentProperty.value.resolvedValue.name}'"
    )
  }

  def buildOverride[P <: GenericProperty[?]](value: P, parent: ParentProperty[?]): P = {
    val r = GenericProperty
      .mergeConfig(
        parent.value.resolvedValue.config,
        value.config
      )(parent.value.resolvedValue.inheritMode)

    // well. fromConfig ensure that it's ok given our sealed hierarchy, but it would be could to get scalac proves it
    value.fromConfig(r).withProvider(GroupProp.OVERRIDE_PROVIDER).asInstanceOf[P]
  }
}

/*
 * A property with its inheritance context as the result of the linearization of the semi-lattice
 * it belongs to:
 * - `value` is the original, non overridden generic property
 * - `resolvedValue` is the overridden value after linearization
 * - `parentProperty` is the (optional) parent in hierarchy
 */
sealed trait PropertyVertex[P <: GenericProperty[?]] {
  def kind:  PropertyVertexKind
  def id:    String // entity (node, group, global) id
  def name:  String // entity name
  def value: PropertyValueKind[P]
}

object PropertyVertex {

  // node is a leaf of a hierarchy - which can be confusing. Keeping node/group/global naming scheme semantic here.
  final case class Node(
      override val name:  String,
      nodeId:             NodeId,
      override val value: PropertyValueKind[NodeProperty]
  ) extends PropertyVertex[NodeProperty] {
    override val kind: PropertyVertexKind = PropertyVertexKind.Node
    override def id:   String             = nodeId.value
  }

  // A node and group can't have a node parent property, only group or global.
  // Not using "node" (as in "not a leaf of the tree") but vertex, to avoid confusion.
  sealed trait ParentProperty[P <: GenericProperty[?]] extends PropertyVertex[P]

  // a group can have groups or global parents
  final case class Group(
      override val name:  String,
      groupId:            NodeGroupId,
      override val value: PropertyValueKind[GroupProperty]
  ) extends ParentProperty[GroupProperty] {
    override val kind: PropertyVertexKind = PropertyVertexKind.Group
    override def id:   String             = groupId.serialize
  }

  // a global parameter has the same name as property so no need to be specific for name
  final case class Global(override val value: PropertyValueKind.SelfValue[GlobalParameter])
      extends ParentProperty[GlobalParameter] {
    override val name: String             = value.resolvedValue.name
    override def id:   String             = value.resolvedValue.name
    override val kind: PropertyVertexKind = PropertyVertexKind.Global
  }

  /*
   * Add given parent property as a new outermost parent of that hierarchy.
   * If the current root is a global property, insert it below that root if
   * it's a group, else ignore (keep existing global property)
   */
  def appendAsRoot(g: PropertyVertex.Group, root: ParentProperty[?]): PropertyVertex.Group = {
    g.value match {
      case PropertyValueKind.SelfValue(value)                  =>
        PropertyVertex.Group(g.name, g.groupId, PropertyValueKind.Overridden(value, root))

      // recurse up
      case PropertyValueKind.Inherited(parentProperty)         =>
        val vk = parentProperty match {
          case p: Group  => appendAsRoot(p, root)
          case p: Global =>
            root match {
              case r: Group  => appendAsRoot(r, p) // insert between
              case _: Global => p                  // keep old
            }
        }
        PropertyVertex.Group(g.name, g.groupId, PropertyValueKind.Inherited(vk)(GroupProperty.apply))

      // recurse up
      case PropertyValueKind.Overridden(value, parentProperty) =>
        val vk = parentProperty match {
          case p: Group  => appendAsRoot(p, root)
          case p: Global =>
            root match {
              case r: Group  => appendAsRoot(r, p) // insert between
              case _: Global => p                  // keep old
            }
        }

        PropertyVertex.Group(g.name, g.groupId, PropertyValueKind.Overridden(value, vk))
    }
  }

  def appendAsRoot(n: PropertyVertex.Node, root: ParentProperty[?]): PropertyVertex.Node = {
    n.value match {
      case PropertyValueKind.SelfValue(value)                  =>
        PropertyVertex.Node(n.name, n.nodeId, PropertyValueKind.Overridden(value, root))

      // recurse up
      case PropertyValueKind.Inherited(parentProperty)         =>
        val vk = parentProperty match {
          case p: Group  => appendAsRoot(p, root)
          case p: Global =>
            root match {
              case r: Group  => appendAsRoot(r, p) // insert between
              case _: Global => p                  // keep old
            }
        }
        PropertyVertex.Node(n.name, n.nodeId, PropertyValueKind.Inherited(vk)(NodeProperty.apply))

      // recurse up
      case PropertyValueKind.Overridden(value, parentProperty) =>
        val vk = parentProperty match {
          case p: Group  => appendAsRoot(p, root)
          case p: Global =>
            root match {
              case r: Group  => appendAsRoot(r, p) // insert between
              case _: Global => p                  // keep old
            }
        }

        PropertyVertex.Node(n.name, n.nodeId, PropertyValueKind.Overridden(value, vk))
    }
  }

}

/**
 * A property with its inheritance/overriding context.
 * The first vertex of the hierarchy can be purely inherited when the corresponding group or
 * node does not override given property.
 */
sealed trait PropertyHierarchy {
  def hierarchy: PropertyVertex[?]
  def prop:      GenericProperty[?] = hierarchy.value.resolvedValue
}

/*
 * Hierarchy for a node.
 */
case class NodePropertyHierarchy protected (id: NodeId, override val hierarchy: PropertyVertex[?]) extends PropertyHierarchy
object NodePropertyHierarchy {

  // if you have a group/global vertex and want a node hierarchy
  def forNodeWithValue(
      nodeName:  String,
      nodeId:    NodeId,
      selfValue: NodeProperty,
      parent:    Option[ParentProperty[?]]
  ): NodePropertyHierarchy = {
    // in that case, we create a purely inherited node vertex
    val v = parent match {
      case Some(p) => PropertyValueKind.Overridden(selfValue, p)
      case None    => PropertyValueKind.SelfValue(selfValue)
    }
    NodePropertyHierarchy(nodeId, PropertyVertex.Node(nodeName, nodeId, v))
  }

  // if you have a group/global vertex and want a node hierarchy
  def forInheritedNode(nodeName: String, nodeId: NodeId, parent: ParentProperty[?]): NodePropertyHierarchy = {
    // in that case, we create a purely inherited node vertex
    val n = PropertyVertex.Node(nodeName, nodeId, PropertyValueKind.Inherited(parent)(NodeProperty.apply))
    NodePropertyHierarchy(nodeId, n)
  }
}

case class GroupPropertyHierarchy protected (id: NodeGroupId, override val hierarchy: ParentProperty[?]) extends PropertyHierarchy
object GroupPropertyHierarchy {

  // if you have a group/global vertex and want a node hierarchy
  def forGroupWithValue(
      groupName: String,
      groupId:   NodeGroupId,
      selfValue: GroupProperty,
      parent:    Option[ParentProperty[?]]
  ): GroupPropertyHierarchy = {
    // in that case, we create a purely inherited node vertex
    val v = parent match {
      case Some(p) => PropertyValueKind.Overridden(selfValue, p)
      case None    => PropertyValueKind.SelfValue(selfValue)
    }
    GroupPropertyHierarchy(groupId, PropertyVertex.Group(groupName, groupId, v))
  }

  // if you have a group/global vertex which is the parent of the group you want
  def forInheritedGroup(groupName: String, groupId: NodeGroupId, parent: ParentProperty[?]): GroupPropertyHierarchy = {
    // in that case, we create a purely inherited group vertex
    val g = PropertyVertex.Group(groupName, groupId, PropertyValueKind.Inherited(parent)(GroupProperty.apply))
    GroupPropertyHierarchy(groupId, g)
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
  def propertiesErrors: Map[String, (GenericProperty[?], NonEmptyChunk[PropertyVertex[?]], String)]
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
      conflicts: Map[GenericProperty[?], NonEmptyChunk[ParentProperty[?]]]
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
    override val propertiesErrors: Map[String, (GenericProperty[?], NonEmptyChunk[PropertyVertex[?]], String)] = {
      conflicts.map {
        case (k, props) =>
          (
            k.name,
            (k, props, conflictMessage(k, props))
          )
      }
    }

    private def conflictMessage(prop: GenericProperty[?], conflicts: NonEmptyChunk[PropertyVertex[?]]): String = {
      def inheritanceName(p: PropertyVertex[?]): String = {
        p match {
          case n: PropertyVertex.Node =>
            n.value match {
              case PropertyValueKind.SelfValue(value)      => n.name
              case PropertyValueKind.Inherited(parent)     => s"${n.name} <- ${inheritanceName(parent)} "
              case PropertyValueKind.Overridden(_, parent) => s"${n.name} <- ${inheritanceName(parent)} "
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
        parents: NonEmptyChunk[ParentProperty[?]]
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

  implicit class ParentPropertyToJSon(val p: PropertyVertex[?]) extends AnyVal {
    def toJson: JValue = {
      p match {
        case PropertyVertex.Global(value) =>
          (
            ("kind"            -> p.kind.entryName)
            ~ ("name"          -> p.name)
            ~ ("value"         -> GenericProperty.toJsonValue(p.value.resolvedValue.value))
            ~ ("resolvedValue" -> GenericProperty.toJsonValue(p.value.resolvedValue.value))
          )

        case _ =>
          (
            ("kind"            -> p.kind.entryName)
            ~ ("name"          -> p.name)
            ~ ("id"            -> p.id)
            ~ ("value"         -> GenericProperty.toJsonValue(p.value match {
              case PropertyValueKind.SelfValue(value)     => value.value
              case PropertyValueKind.Inherited(_)         => ConfigValueFactory.fromAnyRef("")
              case PropertyValueKind.Overridden(value, _) => value.value
            }))
            ~ ("resolvedValue" -> GenericProperty.toJsonValue(p.value.resolvedValue.value))
          )
      }
    }
  }

  implicit class JsonNodePropertyHierarchy(val prop: PropertyHierarchy) extends AnyVal {
    implicit def formats: DefaultFormats.type = DefaultFormats

    private def buildHierarchy(displayParents: PropertyVertex[?] => JValue): JObject = {

      prop.prop.toJsonObj ~ ("hierarchy" -> displayParents(prop.hierarchy)) ~ ("origval" -> GenericProperty.toJsonValue(
        prop.hierarchy.value match {
          case PropertyValueKind.SelfValue(value)     => value.value
          case PropertyValueKind.Inherited(_)         => "".toConfigValue
          case PropertyValueKind.Overridden(value, _) => value.value
        }
      ))

    }

    def toApiJson: JObject = {
      buildHierarchy(list => list.toJson)
    }

    def toApiJsonRenderParents: JObject = {
      def render(v: ConfigValue) = v.render(ConfigRenderOptions.defaults().setOriginComments(false))
      def displayElem(p: PropertyVertex[?]): String = {
        (p.value match {
          case PropertyValueKind.SelfValue(_)     => None
          case PropertyValueKind.Inherited(p)     => Some(displayElem(p))
          case PropertyValueKind.Overridden(_, p) => Some(displayElem(p))
        }).getOrElse("") +
        s"<p>from ${p.kind match {
            case PropertyVertexKind.Global => p.kind.entryName + " property"
            case _                         => p.kind.entryName
          }} <b>${p.name}${if (p.id.isEmpty) {
            ""
          } else s" (${p.id})"}</b>:<pre>${StringEscapeUtils
            .escapeHtml4(p.value match {
              case PropertyValueKind.SelfValue(value)     => render(value.value)
              case PropertyValueKind.Inherited(_)         => ""
              case PropertyValueKind.Overridden(value, _) => render(value.value)
            })}</pre></p>"
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
