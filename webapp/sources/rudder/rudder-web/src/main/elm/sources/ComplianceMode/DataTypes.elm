module ComplianceMode.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed

--
-- All our data types
--

type ComplianceMode = FullCompliance | ChangesOnly | ReportsDisabled | ErrorMode String

type alias UI =
  { hasWriteRights  : Bool
  }

type alias Model =
  { contextPath    : String
  , ui             : UI
  , complianceMode : ComplianceMode
  , newMode        : ComplianceMode
  , globalMode     : ComplianceMode
  }

type Msg
  = Ignore
  | CallApi (Model -> Cmd Msg)
  | UpdateMode ComplianceMode
  | SaveChanges