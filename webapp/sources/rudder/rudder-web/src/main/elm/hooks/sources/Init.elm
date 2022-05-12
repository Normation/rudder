port module Init exposing (..)

import ApiCalls exposing (..)
import DataTypes exposing (..)


-- PORTS / SUBSCRIPTIONS

port successNotification : String -> Cmd msg
port errorNotification   : String -> Cmd msg
port initTooltips        : String -> Cmd msg
port copy                : String -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions model =
  Sub.none

init : { contextPath : String, hasWriteRights : Bool } -> ( Model, Cmd Msg )
init flags =
  let
    initFilters    = Filters "" All Any
    initUi         = UI initFilters flags.hasWriteRights True
    initModel      = Model flags.contextPath initUi "" []
  in
    ( initModel , getHooks initModel )