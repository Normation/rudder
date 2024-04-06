module NodeProperties.DataTypes exposing (..)

import List.Extra
import Http exposing (Error)
import Dict exposing (Dict)
import Json.Encode exposing (Value)

import Ui.Datatable exposing (TableFilters)

--
-- All our data types
--

type ModalState = NoModal | Deletion String | Usage String PropertyUsage

type alias UsageInfo =
  { id : String
  , name : String
  }
type alias PropertyUsage =
  { directives : List UsageInfo
  , techniques : List UsageInfo
  }

type FindUsageIn = Techniques | Directives

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

type alias TableFiltersOnProperty =
  { sortBy    : SortBy
  , sortOrder : SortOrder
  , filter    : String
  }

type alias TableFiltersOnUsage =
  { sortBy      : SortBy
  , sortOrder   : SortOrder
  , filter      : String
  , findUsageIn : FindUsageIn
  , pagination  : TablePagination
  }

type alias TablePagination =
  { pageDirective : Int
  , pageTechnique : Int
  , tableSize: Int
  , totalRow: Int
  }
type alias UI =
  { hasNodeWrite      : Bool
  , hasNodeRead       : Bool
  , loading           : Bool
  , modalState        : ModalState
  , editedProperties  : Dict String EditProperty
  , showMore          : List String
  , filtersOnProperty : TableFiltersOnProperty
  , filtersOnUsage    : TableFiltersOnUsage
  }

type alias Model =
  { contextPath      : String
  , nodeId           : String
  , objectType       : String
  , properties       : List Property
  , newProperty      : EditProperty
  , ui               : UI
  }

getPageMax : TablePagination -> Int
getPageMax pagination =
  if(pagination.tableSize /= 0) then (pagination.totalRow // pagination.tableSize) + 1 else 1

type Msg
  = Ignore
  | Copy String
  | CallApi (Model -> Cmd Msg)
  | SaveProperty String (Result Error (List Property))
  | GetNodeProperties (Result Error (List Property))
  | FindPropertyUsage String (Result Error PropertyUsage)
  | UpdateNewProperty EditProperty
  | UpdateProperty String EditProperty
  | AddProperty
  | DeleteProperty String
  | ToggleEditPopup ModalState
  | ClosePopup Msg
  | ToggleEditProperty String EditProperty Bool
  | UpdateTableFiltersProperty TableFiltersOnProperty
  | UpdateTableFiltersUsage TableFiltersOnUsage
  | ChangeViewUsage
  | ShowMore String
  | UpdateTableSize Int
  | NexPage
  | PreviousPage
  | LastPage
  | FirstPage
  | GoToPage Int

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
