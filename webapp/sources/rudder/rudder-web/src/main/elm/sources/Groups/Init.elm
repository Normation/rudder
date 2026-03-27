module Groups.Init exposing (..)

import Dict

import Groups.ApiCalls exposing (..)
import Groups.DataTypes exposing (..)

import Ui.Datatable exposing (defaultTableFilters, Category, SubCategories(..))


init : { contextPath : String, hasGroupToDisplay : Bool, hasWriteRights : Bool } -> ( Model, Cmd Msg )
init flags =
  let
    initCategory = Category "" "" "" (SubCategories []) []
    initTableFilters  = defaultTableFilters Name
    initTreeFilters   = (TreeFilters "" [])
    initFilters       = Filters initTableFilters initTreeFilters
    initUI       = UI initFilters flags.hasWriteRights True
    initModel    = Model flags.contextPath Loading initUI initCategory Dict.empty
    listInitActions =
      [ getGroupsTree initModel (not flags.hasGroupToDisplay)
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )