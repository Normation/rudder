module Rules.ViewTabNodes exposing (..)

import Dict
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import Maybe.Extra
import Tuple3

import Rules.DataTypes exposing (..)
import Rules.ViewUtils exposing (..)

import Compliance.DataTypes exposing (..)
import Compliance.Utils exposing (displayComplianceFilters, filterDetailsByCompliance)
import Ui.Datatable exposing (SortOrder(..), Category, getAllElems, generateLoadingTable)


nodesTab : Model -> RuleDetails -> Html Msg
nodesTab model details =
  let
    ui = details.ui

    groupFilters = model.ui.groupFilters
    tableFilters = groupFilters.tableFilters
    complianceFilters = model.ui.complianceFilters

    fun = byNodeCompliance model complianceFilters
    nodeRows =  List.map Tuple3.first fun.rows
    rowId = "byNodes/"
    (sortId, sortOrder) = Dict.get rowId ui.openedRows |> Maybe.withDefault ("Node",Asc)
    sort =   case List.Extra.find (Tuple3.first >> (==) sortId) fun.rows of
               Just (_,_,sortFun) -> (\i1 i2 -> sortFun (fun.data model i1) (fun.data model i2))
               Nothing -> (\_ _ -> EQ)
    filter = tableFilters.filter
    childs       = Maybe.withDefault [] (Maybe.map .nodes details.compliance)
    childrenSort = childs
      |> List.filter (\d -> (String.contains filter d.name) || (String.contains filter d.nodeId.value) )
      |> List.filter (filterDetailsByCompliance complianceFilters)
      |> List.sortWith sort
    (nodesChildren, order, newOrder) = case sortOrder of
       Asc -> (childrenSort, "asc", Desc)
       Desc -> (List.reverse childrenSort, "desc", Asc)
    groupsList = getAllElems model.groupsTree
    includedTargets =
      case details.rule.targets of
        [Composition (Or include) _] -> include
        _ -> details.rule.targets
    specialTargets = includedTargets
      |> List.concatMap (\t ->
        case t of
          Special spe ->
            case List.Extra.find (\g -> g.target == spe) groupsList of
              Just gr -> [gr]
              Nothing -> []
          _ -> []
        )
    infoSpecialTarget =
      if List.isEmpty specialTargets then
        text ""
      else
        div[class "callout-fade callout-info"]
        [ text "This rule applies to some special targets: "
        , ul[]
          ( List.map (\t -> li[][b[][text t.name, text ": "], text t.description]) specialTargets )
        , text "The nodes of these targets ", strong[][text "are not displayed "], text "in the following table."
        ]

    nodeComplianceTable =
      if Maybe.Extra.isNothing details.compliance then -- Compliance is not loaded yet
        [ generateLoadingTable True 2 ]
      else
        [ div [class "table-header extra-filters"]
          [ div [class "main-filters"]
            [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value tableFilters.filter
              , onInput (\s -> UpdateGroupFilters {groupFilters | tableFilters = {tableFilters | filter = s}} )
              ][]
            , button [class "btn btn-default btn-sm btn-icon", onClick (UpdateComplianceFilters {complianceFilters | showComplianceFilters = not complianceFilters.showComplianceFilters}), style "min-width" "170px"]
              [ text ((if complianceFilters.showComplianceFilters then "Hide " else "Show ") ++ "compliance filters")
              , i [class ("fa " ++ (if complianceFilters.showComplianceFilters then "fa-minus" else "fa-plus"))][]
              ]
            , button [class "btn btn-default btn-sm btn-refresh", onCustomClick (RefreshComplianceTable details.rule.id)][i [class "fa fa-refresh"][]]
            ]
          , displayComplianceFilters complianceFilters UpdateComplianceFilters
          ]
        , div[class "table-container"]
          [ table [class "dataTable compliance-table"]
            [ thead []
              [ tr [ class "head" ] (List.map (\row -> th [onClick (ToggleRowSort rowId row (if row == sortId then newOrder else Asc)), class ("sorting" ++ (if row == sortId then "_"++order else ""))] [ text row ]) nodeRows)
              ]
            , tbody []
              ( if (List.length childs) <= 0 then
                [ tr[]
                  [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "This rule is not applied on any Node."] ]
                ]
              else if List.length nodesChildren == 0 then
                [ tr[]
                  [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "No nodes match your filter."] ]
                ]
              else
                List.concatMap (\d -> showComplianceDetails fun d rowId ui.openedRows model)  nodesChildren
              )
            ]
          ]
        ]

  in
    div[class "tab-table-content"]
    ( List.append
      [ div [class "table-title mb-3"]
        [ h4 [class "mb-0"][text "Compliance by nodes"]
        , ( if model.ui.hasWriteRights then
            button [class "btn btn-default btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editGroups = True}, tab = Groups})]
            [ text "Select groups", i[class "fa fa-plus-circle" ][]]
          else
            text ""
          )
        ]
      , if details.rule.enabled then
        text ""
      else
        div[class "toggle-checkbox-container"]
        [ div[ class "callout-fade callout-warning"]
          [ label[]
            [ i[class "fa fa-warning"][]
            , text "This rule is "
            , b[][ text "disabled"]
            , text ", it will not be applied on any node."
            ]
          ]
        ]
      ]
      nodeComplianceTable
    )
