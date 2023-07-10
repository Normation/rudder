port module TechniqueVersion.Init exposing (..)

import TechniqueVersion.DataTypes exposing (..)


-- PORTS / SUBSCRIPTIONS
port createDirective   : String -> Cmd msg
port errorNotification : String -> Cmd msg
port initTooltips      : String -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions model =
  Sub.none

init : { contextPath : String, hasWriteRights : Bool, versions : List Technique } -> ( Model, Cmd Msg )
init flags =
  let
    initUi      = UI flags.hasWriteRights False
    initModel   = Model flags.contextPath initUi flags.versions
  in
    ( initModel , Cmd.none )