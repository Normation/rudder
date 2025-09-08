module NodeProperties.DataTypes exposing (..)

import List.Extra
import Http exposing (Error)
import Dict exposing (Dict)
import Json.Encode exposing (Value)
import QuickSearch.Datatypes exposing (SearchResult)
import Ui.Datatable exposing (SortOrder)
--
-- All our data types
--

type ModalState = NoModal | Deletion String | Usage String PropertyUsage

type alias UsageInfo =
  { id : String
  , name : String
  }
type alias PropertyUsage =
  { directives : Maybe SearchResult
  , techniques : Maybe SearchResult
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

type alias InheritedProperties = 
  { properties : List Property
  , errorMessage : Maybe String
  }

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
  , fullHierarchy : ParentProperty
  , errorMessage : Maybe String
  }

type alias ParentGlobalProperty = { valueType : String }
type alias ParentGroupProperty = { id : String, name : String, valueType : String, parent : Maybe ParentProperty }
type alias ParentNodeProperty = { id : String, name : String, valueType : String, parent : Maybe ParentProperty }
type ParentProperty = ParentGlobal ParentGlobalProperty | ParentGroup ParentGroupProperty | ParentNode ParentNodeProperty


type SortOrder = Asc | Desc

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
  , errorMessage     : Maybe String
  }

getPageMax : TablePagination -> Int
getPageMax pagination =
  if(pagination.tableSize /= 0 && pagination.tableSize /= pagination.totalRow) then
    (pagination.totalRow // pagination.tableSize) + 1
  else
    1

extractUsage : List SearchResult -> PropertyUsage
extractUsage result =
  let
    directives =
      result
        |> List.filter (\s -> s.header.type_ == QuickSearch.Datatypes.Directive)
        |> List.head
    techniques =
      result
        |> List.filter (\s -> s.header.type_ == QuickSearch.Datatypes.Technique)
        |> List.head
  in
    PropertyUsage directives techniques

getSearchResultLength : PropertyUsage -> FindUsageIn -> Int
getSearchResultLength result kind =
  case kind of
    Techniques -> result.techniques |> Maybe.map (\d -> d.header.numbers) |> Maybe.withDefault 0
    Directives -> result.directives |> Maybe.map (\d -> d.header.numbers) |> Maybe.withDefault 0

type Msg
  = Ignore
  | Copy String
  | CallApi (Model -> Cmd Msg)
  | SaveProperty String (Result Error (List Property))
  | GetInheritedProperties (Result Error InheritedProperties)
  | FindPropertyUsage String (Result Error (List SearchResult))
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
    getOtherHierarchyValueType : ParentProperty -> List String
    getOtherHierarchyValueType p =
      case p of
        ParentGlobal prop -> [ prop.valueType ]
        ParentGroup prop ->
          let
            parent = case prop.parent of
                       Just par -> getOtherHierarchyValueType par
                       Nothing -> []
          in
            if prop.id /= model.nodeId then prop.valueType :: parent else parent
        ParentNode prop ->
          let
            parent = case prop.parent of
                       Just par -> getOtherHierarchyValueType par
                       Nothing -> []
          in
            if prop.id /= model.nodeId then prop.valueType :: parent else parent
    mergedValueTypes =
      List.Extra.find (\p -> p.name == propertyName) model.properties
      |> Maybe.andThen (\p -> p.hierarchyStatus)
      |> Maybe.map (\hs -> (getOtherHierarchyValueType >> List.map valueTypeToValueFormat) hs.fullHierarchy)
  in
    mergedValueTypes
    |> Maybe.withDefault []
