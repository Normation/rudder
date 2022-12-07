module Init exposing (..)

import ApiCalls exposing (..)
import DataTypes exposing (..)
import Dict exposing (Dict)
import FakeUtils exposing (fakeRuleCompliance)


init : { directiveId : String, contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let
    initFilters  = (TableFilters Asc "" Dict.empty)
    initUI       = UI initFilters initFilters RulesView
    initModel    = Model (DirectiveId flags.directiveId) flags.contextPath "" initUI Nothing Dict.empty Dict.empty
    listInitActions =
      [ getPolicyMode initModel
      , getAllRules   initModel
      , getNodesList  initModel
      , getDirectiveCompliance initModel
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )