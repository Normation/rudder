module GroupCompliance.Init exposing (..)

import Dict

import GroupCompliance.ApiCalls exposing (..)
import GroupCompliance.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)
import Ui.Datatable exposing (TableFilters, SortOrder(..), defaultTableFilters)

init : { groupId : String, contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let
    initFilters  = defaultTableFilters Name
    initUI       = UI initFilters initFilters (ComplianceFilters False False []) RulesView True False
    initModel    = Model (GroupId flags.groupId) flags.contextPath "" initUI Nothing GlobalCompliance
    listInitActions =
      [ getPolicyMode initModel
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )