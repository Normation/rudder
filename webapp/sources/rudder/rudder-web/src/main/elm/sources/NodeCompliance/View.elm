module NodeCompliance.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput)
import List
import Html.Lazy
import Tuple3
import Dict
import List.Extra

import NodeCompliance.DataTypes exposing (..)
import NodeCompliance.ViewUtils exposing (..)
import Compliance.Utils exposing (displayComplianceFilters, filterDetailsByCompliance)
import Ui.Datatable exposing (filterSearch, SortOrder(..), generateLoadingTable)


view : Model -> Html Msg
view model =
  let
    ui = model.ui
    displayComplianceTable : Model -> Html Msg
    displayComplianceTable mod =
      let
        filters = mod.ui.tableFilters
        complianceFilters = mod.ui.complianceFilters
        fun     = byRuleCompliance mod complianceFilters
        col     = "Rule"
        childs  = case mod.nodeCompliance of
          Just dc -> dc.rules
          Nothing -> []
        childrenSort = childs
          |> List.filter (\r -> (filterSearch filters.filter (searchFieldRuleCompliance r)))
          |> List.filter (filterDetailsByCompliance complianceFilters)
          |> List.sortWith sort

        (children, order, newOrder) = case sortOrder of
           Asc -> (childrenSort, "asc", Desc)
           Desc -> (List.reverse childrenSort, "desc", Asc)

        rowId = "by" ++ col ++ "s/"
        rows = List.map Tuple3.first fun.rows
        (sortId, sortOrder) = Dict.get rowId filters.openedRows |> Maybe.withDefault (col, Asc)
        sort =   case List.Extra.find (Tuple3.first >> (==) sortId) fun.rows of
          Just (_,_,sortFun) -> (\i1 i2 -> sortFun (fun.data mod i1) (fun.data mod i2))
          Nothing -> (\_ _ -> EQ)
      in
        ( if mod.ui.loading then
          generateLoadingTable True 2
          else
          div[]
          [ div [class "table-header extra-filters"]
            [ div[class "main-filters"]
              [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value filters.filter, onInput (\s -> (UpdateFilters {filters | filter = s} ))][]
              , button [class "btn btn-default btn-sm btn-icon", onClick (UpdateComplianceFilters {complianceFilters | showComplianceFilters = not complianceFilters.showComplianceFilters}), style "min-width" "170px"]
                [ text ((if complianceFilters.showComplianceFilters then "Hide " else "Show ") ++ "compliance filters")
                , i [class ("fa " ++ (if complianceFilters.showComplianceFilters then "fa-minus" else "fa-plus"))][]
                ]
              , button [class "btn btn-default btn-sm btn-refresh", onClick Refresh ]
                [ i [ class "fa fa-refresh" ] [] ]
              ]
            , displayComplianceFilters complianceFilters UpdateComplianceFilters
            ]
          , div[class "table-container"]
            [ table [class "dataTable compliance-table"]
              [ thead []
                [ tr [ class "head" ]
                  ( List.map (\row -> th [onClick (ToggleRowSort rowId row (if row == sortId then newOrder else Asc)), class ("sorting" ++ (if row == sortId then "_"++order else ""))] [ text row ]) rows )
                ]
              , tbody []
                ( if List.length childs <= 0 then
                  [ tr[]
                    [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "There is no compliance for this directive."] ]
                  ]
                else if List.length children == 0 then
                  [ tr[]
                    [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "No rules match your filter."] ]
                  ]
                else
                  List.concatMap (\d ->  showComplianceDetails fun d "" filters.openedRows mod) children
                )
              ]
            ]
          ])
  in
    div [class "tab-table-content"][Html.Lazy.lazy displayComplianceTable model]