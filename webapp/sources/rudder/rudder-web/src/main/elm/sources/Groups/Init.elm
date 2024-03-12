module Groups.Init exposing (..)

import Dict

import Groups.ApiCalls exposing (..)
import Groups.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)


init : { contextPath : String, hasWriteRights : Bool } -> ( Model, Cmd Msg )
init flags =
  let
    initCategory = Category "" "" "" (SubCategories []) []
    initTableFilters  = (TableFilters Name Asc "")
    initTreeFilters   = (TreeFilters "" [])
    initFilters       = Filters initTableFilters initTreeFilters
    initUI       = UI initFilters NoModal flags.hasWriteRights True
    initModel    = Model flags.contextPath Loading initUI initCategory Dict.empty
    listInitActions =
      [ getGroupsTree initModel True
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )