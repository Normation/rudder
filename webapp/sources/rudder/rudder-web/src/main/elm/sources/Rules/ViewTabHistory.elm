module Rules.ViewTabHistory exposing (..)

import Activity.DataTypes exposing (Activity)
import Html exposing (..)
import Html.Attributes exposing (class)
import Rudder.Table
import Rules.DataTypes exposing (..)


historyTab : Rudder.Table.Model Activity Msg -> Html Msg
historyTab activityTable =
    div [ class "tab" ]
        [ div [ class "main-table" ] [ Html.map RudderTableMsg (Rudder.Table.view activityTable) ]
        ]
