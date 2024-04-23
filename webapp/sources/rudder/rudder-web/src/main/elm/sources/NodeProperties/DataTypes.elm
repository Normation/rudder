module NodeProperties.DataTypes exposing (..)

import List.Extra
import Http exposing (Error)
import Dict exposing (Dict)
import Json.Encode exposing (Value)

import Ui.Datatable exposing (TableFilters)

--
-- All our data types
--

type ModalState = NoModal | Deletion String

type alias EditProperty =
  { name      : String
  , value     : String
  , format    : ValueFormat
  , pristineName  : Bool
  , pristineValue : Bool
  , errorFormat : Bool
  }

type ValueFormat = JsonFormat | StringFormat

type alias Property =
  { name      : String
  , value     : Value
  , provider  : Maybe String
  , hierarchy : Maybe String
  , hierarchyStatus : Maybe HierarchyStatus
  , origval   : Maybe Value
  }

type alias HierarchyStatus =
  { hasChildTypeConflicts : Bool
  , fullHierarchy : List ParentProperty
  }

type alias ParentGlobalProperty = { valueType : String }
type alias ParentGroupProperty = { id : String, name : String, valueType : String }
type alias ParentNodeProperty = { id : String, name : String, valueType : String }
type ParentProperty = ParentGlobal ParentGlobalProperty | ParentGroup ParentGroupProperty | ParentNode ParentNodeProperty

type SortBy
  = Name
  | Format
  | Value

type alias UI =
  { hasNodeWrite     : Bool
  , hasNodeRead      : Bool
  , loading          : Bool
  , modalState       : ModalState
  , editedProperties : Dict String EditProperty
  , showMore         : List String
  , filters          : TableFilters SortBy
  }

type alias Model =
  { contextPath      : String
  , nodeId           : String
  , objectType       : String
  , properties       : List Property
  , newProperty      : EditProperty
  , ui               : UI
  }

type Msg
  = Ignore
  | Copy String
  | CallApi (Model -> Cmd Msg)
  | SaveProperty String (Result Error (List Property))
  | GetNodeProperties (Result Error (List Property))
  | UpdateNewProperty EditProperty
  | UpdateProperty String EditProperty
  | AddProperty
  | DeleteProperty String
  | ToggleEditPopup ModalState
  | ClosePopup Msg
  | ToggleEditProperty String EditProperty Bool
  | UpdateTableFilters (TableFilters SortBy)
  | ShowMore String

valueTypeToValueFormat : String -> ValueFormat
valueTypeToValueFormat valueType =
  case String.toLower valueType of
    "string" -> StringFormat
    _ -> JsonFormat


getPossibleFormatsFromPropertyName : Model -> String -> List ValueFormat
getPossibleFormatsFromPropertyName model propertyName =
  let
    getOtherHierarchyValueType p = 
      case p of
        ParentGlobal { valueType } -> Just valueType
        ParentGroup { id, valueType } -> 
          if id /= model.nodeId then Just valueType else Nothing
        ParentNode { id, valueType } ->
          if id /= model.nodeId then Just valueType else Nothing
    mergedValueTypes = 
      List.Extra.find (\p -> p.name == propertyName) model.properties
      |> Maybe.andThen (\p -> p.hierarchyStatus)
      |> Maybe.map (\hs -> List.filterMap (getOtherHierarchyValueType >> Maybe.map valueTypeToValueFormat) hs.fullHierarchy)
  in
    mergedValueTypes
    |> Maybe.withDefault []