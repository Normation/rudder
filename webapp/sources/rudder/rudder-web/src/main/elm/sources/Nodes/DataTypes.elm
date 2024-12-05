module Nodes.DataTypes exposing (..)

import Http exposing (Error)

import Ui.Datatable exposing (..)
--
-- All our data types
--

type alias NodeId = { value : String }

type alias Node =
  { id       : NodeId
  , hostname : String
  }

type SortBy
  = Id
  | Hostname

type alias UI =
  { hasReadRights : Bool
  , loading       : Bool
  , filters       : TableFilters SortBy
  , editColumns   : Bool
  , columns       : List SortBy
  }

type alias Model =
  { contextPath : String
  , policyMode  : String
  , nodes       : List Node
  , ui          : UI
  }

type Msg
  = Ignore
  | Copy String
  | CallApi (Model -> Cmd Msg)
  | GetNodes (Result Error (List Node))
  | UpdateUI UI
