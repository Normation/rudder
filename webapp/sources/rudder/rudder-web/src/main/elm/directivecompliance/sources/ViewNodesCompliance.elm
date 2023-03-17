module ViewNodesCompliance exposing (..)

import DataTypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput)
import List
import List.Extra
import String
import Tuple3
import Dict
import ApiCalls exposing (..)
import ViewUtils exposing (..)

displayNodesComplianceTable : Model -> Html Msg
displayNodesComplianceTable model =
  let
    filters = model.ui.nodeFilters
    fun     = byNodeCompliance model
    col     = "Node"
    childs  = case model.directiveCompliance of
      Just dc -> dc.nodes
      Nothing -> []
    childrenSort = childs
      |> List.filter (\n -> (filterSearch filters.filter (searchFieldNodeCompliance n model.nodes)))
      |> List.sortWith sort

    (children, order, newOrder) = case sortOrder of
       Asc -> (childrenSort, "asc", Desc)
       Desc -> (List.reverse childrenSort, "desc", Asc)

    rowId = "by" ++ col ++ "s/"
    rows = List.map Tuple3.first fun.rows
    (sortId, sortOrder) = Dict.get rowId filters.openedRows |> Maybe.withDefault (col, Asc)
    sort =   case List.Extra.find (Tuple3.first >> (==) sortId) fun.rows of
      Just (_,_,sortFun) -> (\i1 i2 -> sortFun (fun.data model i1) (fun.data model i2))
      Nothing -> (\_ _ -> EQ)
  in
    ( if model.ui.loading then
      generateLoadingTable
      else
      div[]
      [ div [class "table-header"]
        [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value filters.filter
        , onInput (\s -> (UpdateFilters {filters | filter = s} ))][]
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
                [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "No nodes match your filter."] ]
              ]
            else
              List.concatMap (\d ->  showComplianceDetails fun d "" filters.openedRows model) children
            )
          ]
        ]
      ])
