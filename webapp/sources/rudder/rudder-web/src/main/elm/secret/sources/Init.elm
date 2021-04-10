module Init exposing (..)

import ApiCalls exposing (getAllSecrets)
import DataTypes exposing (Column(..), Mode(..), Model, Msg(..), Sorting(..), StateInput(..))

subscriptions : Model -> Sub Msg
subscriptions model =
  Sub.none

init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let
    initModel = Model flags.contextPath [] Nothing Nothing Read [] [] (NONE, Name) Nothing
  in
  ( initModel
  , getAllSecrets initModel
  )
