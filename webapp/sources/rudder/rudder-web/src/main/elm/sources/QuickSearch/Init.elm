module QuickSearch.Init exposing (..)

import Debounce
import QuickSearch.Datatypes exposing (..)


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

debounceConfig : Debounce.Config Msg
debounceConfig =
  { strategy = Debounce.later 500
  , transform = DebounceMsg
  }