module Plugins.JsonEncoder exposing (..)

import Json.Encode exposing (..)
import Plugins.PluginData exposing (PluginId)
import Set exposing (Set)


encodePluginIds : Set PluginId -> Value
encodePluginIds =
    set string
