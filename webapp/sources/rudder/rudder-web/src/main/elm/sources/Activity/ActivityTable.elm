module Activity.ActivityTable exposing (..)

import Activity.DataTypes exposing (Activity, ActivityMsg)
import Activity.HtmlParserAdapter exposing (toHtml, toString)
import Html exposing (text)
import Html.Attributes exposing (class)
import List.Nonempty as NonEmptyList
import Ordering
import Rudder.Table exposing (ColumnName(..), buildConfig, buildCustomizations, buildOptions)
import Time exposing (Zone)
import Utils.DateUtils exposing (posixToString)


initTable : Zone -> Rudder.Table.Model Activity msg
initTable timezone =
    let
        columns : NonEmptyList.Nonempty (Rudder.Table.Column Activity msg)
        columns =
            NonEmptyList.Nonempty
                { name = ColumnName "Id", renderHtml = .id >> String.fromInt >> text, ordering = Ordering.byField .id }
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
