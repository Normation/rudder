module Groups.ViewGroupsTable exposing (..)

import Maybe
import Html exposing (Html, i, text, td, th, tr)
import Html.Attributes exposing (class, colspan, rowspan)
import Html.Events exposing (onClick)

import Groups.DataTypes exposing (..)
import Groups.ViewUtils exposing (..)

import Compliance.Html exposing (buildComplianceBar)
import Compliance.Utils exposing (defaultComplianceFilter)
import Ui.Datatable exposing (filterSearch, sortTable, thClass)


buildGroupsTable : Model -> List Group -> List(Html Msg)
buildGroupsTable model groups =
  let
    groupsList       = groups
    sortedGroupsList = groupsList
      |> List.filter (\r -> filterSearch model.ui.groupFilters.treeFilters.filter (searchFieldGroups r model))
      |> List.sortWith (getSortFunction model)

    rowTable : Group -> Html Msg
    rowTable r =
      let
        categoryName = text (Maybe.withDefault "Groups" (Maybe.map (getCategoryName model) r.category))

        (targetedCompliance, globalCompliance) =
          case getGroupCompliance model r.id of
            Just co ->
              (buildComplianceBar defaultComplianceFilter co.targeted.complianceDetails
              , buildComplianceBar defaultComplianceFilter co.global.complianceDetails
              )
            Nothing -> (text "No report", text "No report")

      in
            tr[onClick (OpenGroupDetails r.id)]
            [ td[]
              [ text r.name
              ]
            , td[][ categoryName       ]
            , td[class ("compliance-col")][ globalCompliance   ]
            , td[class ("compliance-col")][ targetedCompliance ]
            ]
  in
    if List.length sortedGroupsList > 0 then
      List.map rowTable sortedGroupsList
    else
      [ tr[][td [class "empty", colspan 5][i [class "fa fa-exclamation-triangle"][], text "No groups match your filters."]]]

groupsTableHeader : Filters -> Html Msg
groupsTableHeader groupFilters =
  let
    tableFilters = groupFilters.tableFilters
  in
    tr [class "head"]
    [ th [ class (thClass tableFilters Name) , rowspan 1, colspan 1
       , onClick (UpdateGroupFilters { groupFilters | tableFilters = (sortTable tableFilters Name)})
       ] [ text "Name" ]
    , th [ class (thClass tableFilters Parent) , rowspan 1, colspan 1
      , onClick (UpdateGroupFilters { groupFilters | tableFilters = (sortTable tableFilters Parent)})
      ] [ text "Category" ]
    , th [ class ((thClass tableFilters GlobalCompliance) ++ " compliance-col"), rowspan 1, colspan 1
      , onClick (UpdateGroupFilters { groupFilters | tableFilters = (sortTable tableFilters GlobalCompliance)})
      ] [ text "Global compliance" ]
    , th [ class ((thClass tableFilters TargetedCompliance) ++ " compliance-col"), rowspan 1, colspan 1
      , onClick (UpdateGroupFilters { groupFilters | tableFilters = (sortTable tableFilters TargetedCompliance)})
      ] [ text "Targeted compliance" ]
    ]
