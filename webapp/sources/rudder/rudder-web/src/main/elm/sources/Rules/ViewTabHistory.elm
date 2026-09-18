module Rules.ViewTabHistory exposing (..)

import EventLogs.DataTypes exposing (EventLog)
import Html exposing (..)
import Html.Attributes exposing (class)
import Rudder.Table
import Rules.DataTypes exposing (..)


historyTab : Rudder.Table.Model EventLog Msg -> Html Msg
historyTab historyTable =
    div [ class "tab" ]
        [ div [ class "main-table" ] [ Html.map RudderTableMsg (Rudder.Table.view historyTable) ]
        ]
