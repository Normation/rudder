module Rules.Init exposing (..)

import Dict

import Rules.ApiCalls exposing (..)
import Rules.DataTypes exposing (..)

import Compliance.Utils exposing (defaultComplianceFilter)
import Ui.Datatable exposing (defaultTableFilters, Category, SubCategories(..))


init : { contextPath : String, hasWriteRights : Bool, canReadChanqeRequest : Bool } -> ( Model, Cmd Msg )
init flags =
  let
    initCategory = Category "" "" "" (SubCategories []) []
    initFilters  = Filters (defaultTableFilters Name) (TreeFilters "" [] (Tag "" "") [])
    initUI       = UI initFilters initFilters initFilters defaultComplianceFilter NoModal flags.hasWriteRights flags.canReadChanqeRequest True False False Nothing
    initModel    = Model flags.contextPath Loading "" initCategory initCategory initCategory Dict.empty Dict.empty Dict.empty Dict.empty initUI

    listCRActions =
      if flags.canReadChanqeRequest then
        [ getCrSettingsEnableCr initModel
        , getCrSettingsEnabledMsg initModel
        , getCrSettingsMandatoryMsg initModel
        , getCrSettingsChangeMsgPrompt initModel
        ]
      else []
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
    , Cmd.batch (listInitActions ++  listCRActions)
    )