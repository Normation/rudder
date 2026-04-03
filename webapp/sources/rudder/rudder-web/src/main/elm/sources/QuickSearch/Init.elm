module QuickSearch.Init exposing (..)

import Debounce
import QuickSearch.Model exposing (..)
import QuickSearch.Update exposing (Msg(..))


subscriptions : Model -> Sub Msg
subscriptions _ =
    Sub.none

init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
    let
        initModel = Model "" [] [] flags.contextPath Closed Debounce.init
    in
    ( initModel
    , Cmd.none
    )
