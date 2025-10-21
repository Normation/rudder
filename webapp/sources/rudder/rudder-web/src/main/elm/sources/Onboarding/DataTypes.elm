module Onboarding.DataTypes exposing (..)

import Http exposing (Error)
import List exposing (..)

type Section
  = Welcome
  | Account SectionState AccountSettings
  {- | Metrics SectionState MetricsState -}
  | GettingStarted SectionState

type SectionState
  = Default
  | Visited
  | Completed
  | Warning

type MetricsState
  = NotDefined
  {- | NoMetrics
  | Minimal
  | Complete -}

type alias AccountSettings =
  { username      : Maybe String --- username == License ID
  , password      : Maybe String
  }

type alias Model =
  { contextPath     : String
  , sections        : List Section
  , activeSection   : Int
  , animation       : Bool
  , saveAccountFlag : Bool
  , saveMetricsFlag : Bool
  }

type Msg
  = ChangeActiveSection Int
  | GoToLast
  | UpdateSection Int Section
  | GetAccountSettings  (Result Error AccountSettings)
  {- | GetMetricsSettings  (Result Error MetricsState   ) -}
  | PostAccountSettings (Result Error AccountSettings)
  {- | PostMetricsSettings (Result Error MetricsState   ) -}
  | SetupDone (Result Error Bool   )
  | SaveAction
  | Redirect