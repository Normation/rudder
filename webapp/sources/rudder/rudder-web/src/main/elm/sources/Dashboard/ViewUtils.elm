module Dashboard.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (class, href)
import Html.Events exposing (onClick)

import Dashboard.DataTypes exposing (..)

test : Html Msg
test =
    ul[class "activity-list d-flex flex-column gap-2"]
        [ li[class "activity-item d-flex flex-column w-100"]
            [ span[class "activity-date"][text "2026-02-19 09:13:27Z"]
            , span[class "activity-desc"][text "Group All Linux Nodes managed by root policy server modified"]
            ]
        , li[class "activity-item d-flex flex-column w-100"]
            [ span[class "activity-date"][text "2026-02-19 09:13:27Z"]
            , span[class "activity-desc"][text "Group All Linux Nodes managed by root policy server modified"]
            ]
        ]


activityItem : Activity -> Html Msg
activityItem a =
    li[class "activity-item d-flex flex-column w-100"]
        [ span[class "activity-date"][text a.date]
        , span[class "activity-desc"][text a.description]
        ]