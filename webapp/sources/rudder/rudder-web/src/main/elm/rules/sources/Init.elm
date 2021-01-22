module Init exposing (..)

import ApiCalls exposing (getRulesTree, getTechniques, getDirectives, getPolicyMode, getGroupsTree, getTechniquesTree, getRulesCompliance)
import DataTypes exposing (..)


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none

init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let

    initRulesTree      = Category "" "" [] []
    initGroupsTree     = GroupCat "" "" "" "" [] []
    initTecnhiquesTree = TechniqueCat "" "" [] []
    initRuleUI         = RuleUI (Tag "" "")
    initModel = Model flags.contextPath Information False False Nothing "" initRulesTree [] [] initGroupsTree initTecnhiquesTree [] initRuleUI

    listInitActions =
      [ getPolicyMode      initModel
      , getRulesTree       initModel
      , getTechniques      initModel
      , getDirectives      initModel
      , getGroupsTree      initModel
      , getTechniquesTree  initModel
      , getRulesCompliance initModel
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )