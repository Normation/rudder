module Rules.ViewRepairedReports exposing (..)

import Dict
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Time.Iso8601
import Time.ZonedDateTime as ZDT exposing (ZonedDateTime)
import Time.TimeZone as TZ exposing (TimeZone)

import Rules.DataTypes exposing (..)


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
  List.reverse <| List.indexedMap (\id c -> option [value (String.fromInt id)] [text (showChanges c) ] ) <| Maybe.withDefault [] (Dict.get ruleId.value model.changes )

showTab: Model -> RuleDetails -> Html Msg
showTab model details =

  let
    col = columns model
  in
    div[class "tab-table-content"]
      [ div [class "table-title"]
        [ h4 [][text "Recent changes ", select [onInput (\s -> GetRepairedReport (details.rule.id) (Maybe.withDefault 0 (String.toInt s)) ), class "form-control" ] (options details.rule.id model) ] ]
      , div [class "table-header"]
        [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value model.ui.groupFilters.tableFilters.filter
          , onInput (\s ->
            let
              groupFilters = model.ui.groupFilters
              tableFilters = groupFilters.tableFilters
            in
              UpdateGroupFilters {groupFilters | tableFilters = {tableFilters | filter = s}}
          )][]
        , button [class "btn btn-default", onClick (RefreshReportsTable details.rule.id) ][i [class "fa fa-refresh"][]]
        ]
      , div[class "table-container"] [
          table [class "dataTable compliance-table"] [
            thead [] [
              tr [ class "head" ] (List.map (\row -> th [] [ text row.id ]) col )
            ]
          , tbody [] (
            if List.length details.reports <= 0 then
              [ tr[]
                [ td[class "empty", colspan 6][i [class"fa fa-exclamation-triangle"][], text "No data available."] ]
              ]
            else
              List.map (\r ->
                tr [] (List.map (\c -> td []  [text (c.toRow r)] ) col)
              )  details.reports
            )
          ]
        ]
      ]

showChanges: Changes -> String
showChanges c =
  let
    formatYear = String.fromInt << ZDT.year
    formatTwoDigitInt = String.padLeft 2 '0' << String.fromInt
    formatMonth = formatTwoDigitInt << ZDT.month
    formatDay = formatTwoDigitInt << ZDT.day
    formatHour = formatTwoDigitInt << ZDT.hour
    formatMinute = formatTwoDigitInt << ZDT.minute
    formatTimeZone d = "UTC" ++ (String.left 3 <| TZ.offsetString (ZDT.toPosix d) (ZDT.timeZone d))
    formatChangeDate d = (formatYear d) ++ "-" ++ (formatMonth d) ++ "-" ++ (formatDay d) ++ " " ++ (formatHour d) ++ ":" ++ (formatMinute d) ++ " " ++ (formatTimeZone d)
    formatChangeCount n = (String.fromFloat n) ++ (if n > 1 then " changes" else " change")
  in
    ("Between " ++ (formatChangeDate c.start) ++  " and " ++ (formatChangeDate c.end) ++ " (" ++ formatChangeCount c.changes) ++ ")"