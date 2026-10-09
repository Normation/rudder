module EventLogs.Table exposing (..)

import EventLogs.DataTypes exposing (ContextPath(..), EventLog, EventLogsMsg)
import EventLogs.HtmlParserAdapter exposing (toHtml, toString)
import Html exposing (Html, a, text)
import Html.Attributes exposing (class, href)
import Iso8601 exposing (fromTime)
import Json.Encode exposing (Value, bool, encode, int, list, object, string)
import List.Nonempty as NonEmptyList
import Ordering
import Rudder.Table exposing (ColumnName(..), SortOrder(..), buildConfig, buildCustomizations, buildOptions)
import Time exposing (Posix, Zone)
import Time.Extra
import Utils.DateUtils exposing (posixToString)


initTable : Bool -> ContextPath -> Zone -> Rudder.Table.Model EventLog msg
initTable canReadChangeLogs (ContextPath contextPath) timezone =
    let
        {-
           Add a link on the id to navigate to the detail of this event log on change log page.
           Build the json parameters to query on this event log with the log id.
           {
             "id":{"value":1234,"regex":false,"fixed":[]},
             "startDate":"2026-09-14 00:00:00",
             "endDate":"2026-09-14 17:17:08"
             "draw":1,
             "start":0,
             "length":5
           }
        -}
        idWithLinkToChangeLogsPage : EventLog -> Html msg
        idWithLinkToChangeLogsPage eventLog =
            let
                id =
                    object
                        [ ( "value", eventLog.id |> int )
                        , ( "regex", bool False )
                        , ( "fixed", list bool [] )
                        ]

                calculateDate : Zone -> Posix -> (Int -> Int) -> Posix
                calculateDate zone posix operation =
                    Time.Extra.posixToParts zone posix
                        |> (\parts -> Time.Extra.partsToPosix zone { parts | hour = parts.hour, minute = operation parts.minute, second = parts.second })

                json =
                    object
                        [ ( "id", id )
                        , ( "startDate", string (fromTime (calculateDate timezone eventLog.date (\min -> min - 1))) )
                        , ( "endDate", string (fromTime (calculateDate timezone eventLog.date (\min -> min + 1))) )
                        , ( "draw", int 1 )
                        , ( "start", int 0 )
                        , ( "length", int 5 )
                        ]
                        |> encode 0
            in
            a
                [ href
                    (contextPath
                        ++ "/secure/configurationManager/changeLogs#"
                        ++ json
                    )
                ]
                [ text (String.fromInt eventLog.id) ]

        idWithoutLink : Int -> Html msg
        idWithoutLink eventLogId =
            text (String.fromInt eventLogId)

        columns : NonEmptyList.Nonempty (Rudder.Table.Column EventLog msg)
        columns =
            NonEmptyList.Nonempty
                { name = ColumnName "Id"
                , renderHtml =
                    \eventLog ->
                        if canReadChangeLogs then
                            idWithLinkToChangeLogsPage eventLog

                        else
                            idWithoutLink eventLog.id
                , ordering = Ordering.byField .id
                }
                [ { name = ColumnName "User", renderHtml = .actor >> text, ordering = Ordering.byField .actor }
                , { name = ColumnName "Description"
                  , renderHtml = .description >> toHtml
                  , ordering = Ordering.byField (.description >> toString)
                  }
                , { name = ColumnName "Date", renderHtml = .date >> posixToString timezone >> text, ordering = Ordering.byField (.date >> Time.posixToMillis) }
                ]

        config =
            buildConfig.newConfig columns
                |> buildConfig.withOptions
                    (buildOptions.newOptions
                        |> buildOptions.withCustomizations
                            (buildCustomizations.newCustomizations
                                |> buildCustomizations.withTableContainerAttrs [ class "table-container" ]
                                |> buildCustomizations.withTableAttrs [ class "no-footer dataTable" ]
                            )
                    )
                |> buildConfig.withSortBy (ColumnName "Date")
                |> buildConfig.withSortOrder Desc
    in
    Rudder.Table.init config []
