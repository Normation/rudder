module EventLogs.Table exposing (..)

import EventLogs.DataTypes exposing (ContextPath(..), EventLog, EventLogsMsg)
import EventLogs.HtmlParserAdapter exposing (toHtml, toString)
import Html exposing (Html, a, text)
import Html.Attributes exposing (class, href)
import Json.Encode exposing (Value, bool, encode, int, list, object, string)
import List.Nonempty as NonEmptyList
import Ordering
import Rudder.Table exposing (ColumnName(..), buildConfig, buildCustomizations, buildOptions)
import Time exposing (Zone)
import Utils.DateUtils exposing (posixToString)


initTable : ContextPath -> Zone -> Rudder.Table.Model EventLog msg
initTable (ContextPath contextPath) timezone =
    let
        {-
           Add a link on the id to navigate to the detail of this event log on change log page.
           Build the json parameters to query on this event log with the log id.
           {
             "id":{"value":1234,"regex":false,"fixed":[]},
             "draw":1,
             "start":0,
             "length":5
           }
        -}
        idWithLink : EventLog -> Html msg
        idWithLink eventLog =
            let
                id =
                    object
                        [ ( "value", eventLog.id |> int )
                        , ( "regex", bool False )
                        , ( "fixed", list bool [] )
                        ]

                json =
                    object
                        [ ( "id", id )
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

        columns : NonEmptyList.Nonempty (Rudder.Table.Column EventLog msg)
        columns =
            NonEmptyList.Nonempty
                { name = ColumnName "Id"
                , renderHtml = \eventLog -> idWithLink eventLog
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
    in
    Rudder.Table.init config []
