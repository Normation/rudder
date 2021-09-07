module Init exposing (..)

import ApiCalls exposing (..)
import DataTypes exposing (..)


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none

init : { contextPath : String, hasWriteRights : Bool } -> ( Model, Cmd Msg )
init flags =
  let

    initCategory      = Category "" "" "" (SubCategories []) []
    initModel = Model flags.contextPath Loading "" initCategory initCategory initCategory [] [] Nothing flags.hasWriteRights

    listInitActions =
      [ getPolicyMode      initModel
      , getRulesTree       initModel
      , getGroupsTree      initModel
      , getTechniquesTree  initModel
      , getRulesCompliance initModel
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )