module Init exposing (..)

import ApiCalls exposing (..)
import DataTypes exposing (..)
import Dict

-- PORTS
init : { contextPath : String, hasWriteRights : Bool } -> ( Model, Cmd Msg )
init flags =
  let

    initCategory = Category "" "" "" (SubCategories []) []
    initFilters  = Filters (TableFilters Name Asc "" []) (TreeFilters "" [])
    initUI       = UI initFilters initFilters initFilters NoModal flags.hasWriteRights
    initModel    = Model flags.contextPath Loading "" initCategory initCategory initCategory Dict.empty Dict.empty Dict.empty Dict.empty initUI

    listInitActions =
      [ getPolicyMode      initModel
      , getNodesList       initModel
      , getRulesCompliance initModel
      , getGroupsTree      initModel
      , getTechniquesTree  initModel
      , getRulesTree       initModel
      , getRuleChanges     initModel
      ]


  in

    ( initModel
    , Cmd.batch listInitActions
    )