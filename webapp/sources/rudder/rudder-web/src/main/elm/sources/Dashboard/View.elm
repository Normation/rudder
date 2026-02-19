module Dashboard.View exposing (..)

import Dashboard.DataTypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (class, href)
import Html.Events exposing (onClick)
import List


view : Model -> Html Msg
view model =
    let
        activityItem : Activity -> Html Msg
        activityItem a =
            li[class "activity-item d-flex flex-column w-100"]
                [ span[class "activity-date"][text a.date]
                , span[class "activity-desc"][text a.description]
                ]
    in
        div []
            [ ul[class "activity-list d-flex flex-column mb-0 gap-2"] ( List.map activityItem model.activities )
            ]
