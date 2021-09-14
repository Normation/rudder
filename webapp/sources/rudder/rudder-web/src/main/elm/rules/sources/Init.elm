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
    initRuleFilters   = RuleFilters Name True ""
    initUI = UI initRuleFilters NoModal flags.hasWriteRights
    initModel = Model flags.contextPath Loading "" initCategory initCategory initCategory [] [] initUI

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