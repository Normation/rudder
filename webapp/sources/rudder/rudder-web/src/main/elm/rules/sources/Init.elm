module Init exposing (..)

import ApiCalls exposing (getRulesTree, getTechniques, getDirectives, getPolicyMode, getGroupsTree)
import DataTypes exposing (..)


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none

init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let

    initRulesTree  = Category "" "" [] []
    initGroupsTree = GroupCat "" "" "" "" [] []
    initModel = Model flags.contextPath Information False False Nothing "" initRulesTree [] [] initGroupsTree []

    listInitActions =
      [ getPolicyMode      initModel
      , getRulesTree       initModel
      , getTechniques      initModel
      , getDirectives      initModel
      , getGroupsTree      initModel
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )