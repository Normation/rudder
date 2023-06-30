module ComplianceMode.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed

--
-- All our data types
--

type ComplianceMode = FullCompliance | ChangesOnly | ReportsDisabled | UnknownMode

type alias UI =
  { hasWriteRights  : Bool
  }

type alias Model =
  { contextPath    : String
  , ui             : UI
  , complianceMode : ComplianceMode
  , globalMode     : ComplianceMode
  , newMode        : ComplianceMode
  }

type Msg
  = Ignore
  | CallApi (Model -> Cmd Msg)
  | UpdateMode ComplianceMode
  | SaveChanges