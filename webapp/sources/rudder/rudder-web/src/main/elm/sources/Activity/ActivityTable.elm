module Activity.ActivityTable exposing (..)

import Activity.DataTypes exposing (Activity, ActivityMsg, ContextPath(..))
import Activity.HtmlParserAdapter exposing (toHtml, toString)
import Html exposing (Html, a, text)
import Html.Attributes exposing (class, href)
import Json.Encode exposing (Value, bool, encode, int, list, object, string)
import List.Nonempty as NonEmptyList
import Ordering
import Rudder.Table exposing (ColumnName(..), buildConfig, buildCustomizations, buildOptions)
import Time exposing (Zone)
import Utils.DateUtils exposing (posixToString, posixToStringWithHoursMinutesAndSecondsTo0, posixToStringWithoutTimeZoneOffset)


initTable : ContextPath -> Zone -> Rudder.Table.Model Activity msg
initTable (ContextPath contextPath) timezone =
    let
        {-
           Add a link on the id to navigate to the detail of this activity log on change log page.
           Build the json parameters to query on this activity log with the directive id as search value and activity
           date as start date:
           {
             "start":0,
             "length":5,
             "search":{"value":"123e4567-e89b-12d3-a456-426614174000","regex":false,"fixed":[]},
             "startDate":"2026-07-29 10:49:48",
             "draw":1
           }
        -}
        idWithLink : Activity -> Html msg
        idWithLink activity =
            let
                search =
                    object
                        [ ( "value", activity.id |> String.fromInt |> string )
                        , ( "regex", bool False )
                        , ( "fixed", list bool [] )
                        ]

                json =
                    object
                        [ ( "search", search )
                        , ( "startDate", string (posixToStringWithHoursMinutesAndSecondsTo0 timezone activity.date) )
                        , ( "endDate", string (posixToStringWithoutTimeZoneOffset timezone activity.date) )
                        , ( "draw", int 1 )
                        , ( "start", int 0 )
                        , ( "length", int 10 )
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
                [ text (String.fromInt activity.id) ]

        columns : NonEmptyList.Nonempty (Rudder.Table.Column Activity msg)
        columns =
            NonEmptyList.Nonempty
                { name = ColumnName "Id"
                , renderHtml = \activity -> idWithLink activity
                , ordering = Ordering.byField .id
                }
                [ { name = ColumnName "Actor", renderHtml = .actor >> text, ordering = Ordering.byField .actor }
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
