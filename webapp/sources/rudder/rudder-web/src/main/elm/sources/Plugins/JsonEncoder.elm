module Plugins.JsonEncoder exposing (..)

import Json.Encode exposing (..)
import Plugins.DataTypes exposing (..)
import Set exposing (Set)


encodePluginIds : Set PluginId -> Value
encodePluginIds =
    set string
