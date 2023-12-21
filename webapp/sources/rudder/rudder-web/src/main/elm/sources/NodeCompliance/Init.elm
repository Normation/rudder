module NodeCompliance.Init exposing (..)

import Dict exposing (Dict)

import NodeCompliance.ApiCalls exposing (..)
import NodeCompliance.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)

init : { nodeId : String, contextPath : String, onlySystem : Bool} -> ( Model, Cmd Msg )
init flags =
  let
    initFilters  = (TableFilters Asc "" Dict.empty)
    initUI       = UI initFilters (ComplianceFilters False False []) True
    initModel    = Model (DirectiveId flags.nodeId) flags.contextPath "" initUI Nothing flags.onlySystem [] []
    listInitActions =
      [ getPolicyMode initModel
      , getNodeCompliance initModel
      , getRulesInfo initModel
      , getDirectivesInfo initModel
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )