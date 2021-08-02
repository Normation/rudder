module Init exposing (..)

import ApiCalls exposing (..)
import DataTypes exposing (..)


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none

init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let

    initCategory      = Category "" "" (SubCategories []) []
    initModel = Model flags.contextPath Loading "" initCategory initCategory initCategory [] [] Nothing

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