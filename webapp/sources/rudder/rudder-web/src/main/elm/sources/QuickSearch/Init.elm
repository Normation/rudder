module QuickSearch.Init exposing (init, subscriptions)

import QuickSearch.Model exposing (initModel, Model)
import QuickSearch.Update exposing (Msg(..))


subscriptions : Model -> Sub Msg
subscriptions _ = Sub.none

init : { contextPath : String } -> ( Model, Cmd Msg )
init flags = ( initModel flags , Cmd.none )
