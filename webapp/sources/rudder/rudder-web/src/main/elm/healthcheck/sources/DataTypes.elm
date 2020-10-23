module DataTypes exposing (..)

import Http exposing (Error)

type TabMenu
    = General
    | Details

type SeverityLevel
    = Critical
    | Warning
    | CheckPassed

type alias Check =
    { name  : String
    , msg   : String
    , level : SeverityLevel
    }

type alias Model =
    { contextPath : String
    , healthcheck : List Check
    , tab         : TabMenu
    , showChecks  : Bool
    }

type Msg
    = GetHealthCheckResult (Result Error (List Check))
    | ChangeTabFocus TabMenu
    | CallApi (Model -> Cmd Msg)
    | CheckListDisplay
