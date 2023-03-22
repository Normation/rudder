module DataTypes exposing (..)

import Http exposing (Error)

--
-- All our data types
--

type alias UI =
  { hasWriteRights : Bool
  }

type PolicyMode
  = Default
  | Audit
  | Enforce
  | None

type Form
  = NodeForm String
  | GlobalForm


type alias Schedule =
  { overrides   : Maybe Bool
  , interval    : Int
  , startHour   : Int
  , startMinute : Int
  , splayHour   : Int
  , splayMinute : Int
  }

type alias Model =
  { contextPath      : String
  , globalRun        : Maybe Schedule
  , currentSettings  : Maybe Schedule
  , selectedSettings : Maybe Schedule
  , ui               : UI
  }

type Msg
  = Ignore
  | SaveChanges Schedule
  | UpdateSchedule Schedule