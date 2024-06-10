module DirectiveCompliance.Init exposing (..)

import Dict exposing (Dict)

import DirectiveCompliance.ApiCalls exposing (..)
import DirectiveCompliance.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)
import Compliance.Utils exposing (defaultComplianceFilter)
import Ui.Datatable exposing (defaultTableFilters )


init : { directiveId : String, contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let
    initFilters  = (defaultTableFilters Name)
    initUI       = UI initFilters initFilters defaultComplianceFilter RulesView True False
    initModel    = Model (DirectiveId flags.directiveId) flags.contextPath "" initUI Nothing
    listInitActions =
      [ getPolicyMode initModel
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )