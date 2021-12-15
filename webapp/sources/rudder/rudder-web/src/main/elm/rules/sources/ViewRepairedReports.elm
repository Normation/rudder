module ViewRepairedReports exposing (..)

import DataTypes exposing (..)
import Dict
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Time.Iso8601


type alias TableColumn =
  { id : String
  , toRow: RepairedReport -> String
  }

columns : Model -> List TableColumn
columns model =
  TableColumn "Execution date" (.executionDate >> Time.Iso8601.fromZonedDateTime) ::
  TableColumn "Directive" (\r -> Maybe.withDefault r.directiveId.value (Maybe.map .displayName (Dict.get r.directiveId.value model.directives))) ::
  TableColumn "Node" (\r -> Maybe.withDefault r.nodeId.value (Maybe.map .hostname (Dict.get r.nodeId.value model.nodes))) ::
  TableColumn "Component" (.component) ::
  TableColumn "Value" (.value) ::
  TableColumn "Message" (.message) ::
  []

options: RuleId ->  Model -> List (Html Msg)
options ruleId model =
  List.reverse <| List.indexedMap (\id c -> option [value (String.fromInt id)] [text ("Between " ++ (Time.Iso8601.fromZonedDateTime c.start) ++  " and " ++ (Time.Iso8601.fromZonedDateTime c.end) ++ " ( " ++ (String.fromFloat c.changes) ++ " changes)") ] ) <| Maybe.withDefault [] (Dict.get ruleId.value model.changes )

showTab: Model -> RuleDetails -> Html Msg
showTab model details =

  let
    col = columns model
  in
    div[class "tab-table-content"]
      [ div [class "table-title"]
        [ h4 [][text "Recent changes - ", select [onInput (\s -> GetRepairedReport (details.rule.id) (Maybe.withDefault 0 (String.toInt s)) ), class "form-control" ] (options details.rule.id model) ] ]
      , div [class "table-header"]
        [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value model.ui.groupFilters.tableFilters.filter
          , onInput (\s ->
            let
              groupFilters = model.ui.groupFilters
              tableFilters = groupFilters.tableFilters
            in
              UpdateGroupFilters {groupFilters | tableFilters = {tableFilters | filter = s}}
          )][]
        , button [class "btn btn-primary btn-sm"][text "Refresh"]
        ]
      , div[class "table-container"] [
          table [class "dataTable compliance-table"] [
            thead [] [
              tr [ class "head" ] (List.map (\row -> th [] [ text row.id ]) col )
            ]
          , tbody [] (List.map (\r ->
              tr [] (List.map (\c -> td []  [text (c.toRow r)] ) col)
            )  details.reports)
          ]
        ]
      ]