module ReportingMode.DataTypes exposing (..)

--
-- All our data types
--

type ReportingMode = FullCompliance | ChangesOnly | ReportsDisabled | ErrorMode String

type alias UI =
  { hasWriteRights  : Bool
  }

type alias Model =
  { contextPath   : String
  , ui            : UI
  , reportingMode : ReportingMode
  , newMode       : ReportingMode
  , globalMode    : ReportingMode
  }

type Msg
  = Ignore
  | CallApi (Model -> Cmd Msg)
  | UpdateMode ReportingMode
  | SaveChanges
