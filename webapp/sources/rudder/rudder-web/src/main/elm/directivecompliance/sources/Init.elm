module Init exposing (..)

import ApiCalls exposing (..)
import DataTypes exposing (..)
import Dict exposing (Dict)

init : { directiveId : String, contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let
    initFilters  = (TableFilters Asc "" Dict.empty)
    initUI       = UI initFilters initFilters RulesView True False
    initModel    = Model (DirectiveId flags.directiveId) flags.contextPath "" initUI Nothing Dict.empty Dict.empty
    listInitActions =
      [ getPolicyMode initModel
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )