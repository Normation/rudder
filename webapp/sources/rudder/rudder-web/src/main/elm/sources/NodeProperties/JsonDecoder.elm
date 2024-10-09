module NodeProperties.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)

import NodeProperties.DataTypes exposing (..)


-- GENERAL
decodeGetInheritedProperties : Decoder InheritedProperties
decodeGetInheritedProperties =
  at [ "data" ] (index 0 decodeInheritedProperties)

decodeGetGroupInheritedProperties : Decoder InheritedProperties
decodeGetGroupInheritedProperties =
  at [ "data", "groups" ] (index 0 decodeInheritedProperties)

decodeSaveProperties : Decoder (List Property)
decodeSaveProperties =
  at [ "data" ] decodeProperties

decodeSaveGroupProperties : Decoder (List Property)
decodeSaveGroupProperties =
  at [ "data", "groups" ] (index 0 decodeProperties)

decodeProperties : Decoder (List Property)
decodeProperties =
  at [ "properties" ] (list decodeProperty)

decodeInheritedProperties : Decoder InheritedProperties
decodeInheritedProperties =
  succeed InheritedProperties
    |> required "properties" (list decodeProperty)
    |> optional "errorMessage" (map Just string) Nothing

decodeProperty : Decoder Property
decodeProperty =
  succeed Property
    |> required "name"      string
    |> required "value"     value
    |> optional "provider"  (map Just string) Nothing
    |> optional "hierarchy" (map Just string) Nothing
    |> optional "hierarchyStatus" (map Just decodeHierarchyStatus) Nothing
    |> optional "origval"   (map Just value) Nothing

decodeHierarchyStatus : Decoder HierarchyStatus
decodeHierarchyStatus =
  succeed HierarchyStatus
    |> required "hasChildTypeConflicts" bool
    |> required "fullHierarchy" (list decodeParentProperty)
    |> optional "errorMessage" (map Just string) Nothing

decodeParentProperty : Decoder ParentProperty
decodeParentProperty =
  field "kind" string |> andThen (\s ->
    case s of
      "group" -> 
        map3 ParentGroupProperty
          (field "id" string)
          (field "name" string)
          (field "valueType" string)
          |> map ParentGroup
      "node" ->
        map3 ParentNodeProperty
          (field "id" string)
          (field "name" string)
          (field "valueType" string)
          |> map ParentNode
      "global" -> 
        map ParentGlobalProperty
          (field "valueType" string)
          |> map ParentGlobal
      _ -> fail "Invalid parent property kind"
  )
