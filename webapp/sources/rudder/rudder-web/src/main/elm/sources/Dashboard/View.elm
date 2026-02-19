module Dashboard.View exposing (..)

import Dashboard.DataTypes exposing (..)
import Dashboard.ViewUtils exposing (test)
import Html exposing (..)
import Html.Attributes exposing (class, href)
import Html.Events exposing (onClick)
import List


view : Model -> Html Msg
view model =
    let
        txt = test
        activities = model.activities
    in
    div [ ]
        [ text txt
        , ul[]
            ( activities
                |> List.map( \a ->
                    li[][text a.name]
                )
            )
        ]
