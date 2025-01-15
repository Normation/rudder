module Plugins.JsonEncoder exposing (..)

import Json.Encode exposing (..)
import Plugins.DataTypes exposing (..)


encodePluginIds : List PluginId -> Value
encodePluginIds =
    list string
