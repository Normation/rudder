module NodeCompliance.Init exposing (..)

import Dict exposing (Dict)

import NodeCompliance.ApiCalls exposing (..)
import NodeCompliance.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)
import Compliance.Utils exposing (defaultComplianceFilter)

init : { nodeId : String, contextPath : String, onlySystem : Bool} -> ( Model, Cmd Msg )
init flags =
  let
    initFilters  = (TableFilters Asc "" Dict.empty)
    initUI       = UI initFilters defaultComplianceFilter True
    initModel    = Model (DirectiveId flags.nodeId) flags.contextPath "" initUI Nothing flags.onlySystem
    listInitActions =
      [ getPolicyMode initModel
      , getNodeCompliance initModel
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )