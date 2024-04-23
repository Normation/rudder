module Ui.Datatable exposing (..)

import Dict exposing (Dict)
import Html exposing (Html, table, thead, tbody, tr, th, td, div, span, text)
import Html.Attributes exposing (class, style)

type SortOrder
    = Asc
    | Desc
{--
type SortBy
    = Hostname
    | PolicyServer
    | Ram
    | AgentVersion
    | Software String
    | NodeProperty String Bool
    | PolicyMode
    | IpAddresses
    | MachineType
    | Kernel
    | Os
    | NodeCompliance
    | LastRun
    | InventoryDate
    | Name
    | Id
    | ExpDate
    | CreDate
    | Parent
    | Status
    | Compliance
    | RuleChanges
    | FileName
    | FileSize
    | FileDate
    | FileRights
    | GlobalCompliance
    | TargetedCompliance
    | Format
    | Value
--}
type alias TableFilters sortBy =
  { sortBy     : sortBy
  , sortOrder  : SortOrder
  , filter     : String
  , openedRows : Dict String (String, SortOrder)
  }

defaultTableFilters : sortBy -> TableFilters sortBy
defaultTableFilters sortBy =
    TableFilters sortBy Asc "" Dict.empty

thClass : TableFilters sortBy -> sortBy -> String
thClass tableFilters sortBy =
  if sortBy == tableFilters.sortBy then
    case  tableFilters.sortOrder of
      Asc  -> "sorting_asc"
      Desc -> "sorting_desc"
  else
    "sorting"

sortTable : TableFilters sortBy -> sortBy -> TableFilters sortBy
sortTable tableFilters sortBy =
  let
    order =
      case tableFilters.sortOrder of
        Asc -> Desc
        Desc -> Asc
  in
    if sortBy == tableFilters.sortBy then
      { tableFilters | sortOrder = order}
    else
      { tableFilters | sortBy = sortBy, sortOrder = Asc}

filterSearch : String -> List String -> Bool
filterSearch filterString searchFields =
    let
        -- Join all the fields into one string to simplify the search
        stringToCheck =
            searchFields
                |> String.join "|"
                |> String.toLower

        searchString =
            filterString
                |> String.toLower
                |> String.trim
    in
        String.contains searchString stringToCheck



--
-- COMPLIANCE TABLES
--
type alias Category a =
  { id          : String
  , name        : String
  , description : String
  , subElems    : SubCategories a
  , elems       : List a
  }

type SubCategories a = SubCategories (List (Category a))

getAllElems : Category a -> List a
getAllElems category =
    let
        subElems =
            case category.subElems of
                SubCategories l ->
                    l
    in
    List.append category.elems (List.concatMap getAllElems subElems)


getSubElems : Category a -> List (Category a)
getSubElems cat =
    case cat.subElems of
        SubCategories subs ->
            subs


getAllCats : Category a -> List (Category a)
getAllCats category =
    let
        subElems =
            case category.subElems of
                SubCategories l ->
                    l
    in
    category :: List.concatMap getAllCats subElems


emptyCategory : Category a
emptyCategory =
    Category "" "" "" (SubCategories []) []


---
--- LOADING ANIMATION
---
generateLoadingTable : Bool -> Int -> Html msg
generateLoadingTable withFilter nbColumns =
    let
        nbRows = 20
        filter =
            if withFilter then
                div [class "dataTables_wrapper_top table-filter"]
                [ div [class "form-group"]
                    [ span[][]
                    ]
                ]
            else
                text ""
    in
        div [class "table-container skeleton-loading"]
          [ filter
          , table [class "dataTable"]
            [ thead []
              [ tr [class "head"]
                ( List.repeat nbColumns (th [][ span[][] ])
                )
              ]
            , tbody []
              ( List.repeat nbRows
                ( tr [] ( List.repeat (nbColumns) ( td[][span[][]] ) )
                )
              )
            ]
          ]