module View exposing (..)

import DataTypes exposing (Check, Model, Msg(..), SeverityLevel(..))
import Html exposing (Html, button, div, i, span, text)
import Html.Attributes exposing (class)
import Html.Events exposing (onClick)
import List exposing (any, map, sortWith)
import List.Extra exposing (minimumWith)
import String

compareSeverityLevel: SeverityLevel -> SeverityLevel -> Order
compareSeverityLevel a b =
  case (a, b) of
      (Critical, Warning)        -> LT
      (Critical, CheckPassed)    -> LT
      (Warning, Critical)        -> GT
      (Warning, CheckPassed)     -> LT
      (CheckPassed, Warning)     -> GT
      (CheckPassed, Critical)    -> GT
      (Warning, Warning)         -> EQ
      (CheckPassed, CheckPassed) -> EQ
      (Critical, Critical)       -> EQ

compareCheck: Check -> Check -> Order
compareCheck a b =
  let
    orderLevel = compareSeverityLevel a.level b.level
  in
  case orderLevel of
    EQ -> if(a.name > b.name) then GT else LT
    _  -> orderLevel

containsSeverityLevel: SeverityLevel -> List Check -> Bool
containsSeverityLevel level checks =
  any (\c -> c.level == level) checks

chooseHigherSecurityLevel: List Check -> SeverityLevel
chooseHigherSecurityLevel checks =
  let
    higherPriority = minimumWith (\c1 c2 -> compareSeverityLevel c1.level c2.level ) checks
  in
  case higherPriority of
    Just c  -> c.level
    Nothing -> CheckPassed -- empty list

severityLevelToString: SeverityLevel  -> String
severityLevelToString level =
  case level of
    Warning     -> "warning"
    CheckPassed -> "ok"
    Critical    -> "critical"

severityLevelToIcon: SeverityLevel  -> Html Msg
severityLevelToIcon level =
  case level of
    Critical    -> i [class "fa fa-times-circle critical-icon icon-info"][]
    Warning     -> i [class "fa fa-exclamation-circle warning-icon icon-info"][]
    CheckPassed -> i [class "fa fa-check-circle ok-icon icon-info"][]

displayCheckListButton: Bool -> Html Msg
displayCheckListButton isOpen =
    let
        classNameBtn = "btn-checks btn btn-outline-secondary"
    in
    if isOpen then
      button [class classNameBtn, onClick CheckListDisplay]
      [
          text "Hide check's list"
        , i [class "fa fa-chevron-down"][]
      ]
    else
      button [class classNameBtn, onClick CheckListDisplay]
      [
          text "Show check's list"
        , i [class "fa fa-chevron-right"][]
      ]

displayBigMessage: List Check -> Html Msg
displayBigMessage checks =
  let
    level = chooseHigherSecurityLevel checks
    levelStr = severityLevelToString level
  in
  div[class "global-msg"]
  [
    div [class (levelStr ++ "-info")]
    [
        severityLevelToIcon level
      , case level of
          Critical    -> text "Critical Error"
          Warning     -> text "Should be improved"
          CheckPassed -> text "All check passed"
    ]
  ]

checksDisplay: Model -> List Check -> Html Msg
checksDisplay model h =
  let
    sortedChecks = sortWith compareCheck h
    content =
      map (
           \c ->
             let
               classNameCircle = (severityLevelToString c.level) ++ "-light circle "
             in
               div [class "check"]
               [ text c.msg, span [class classNameCircle][] ]

     ) sortedChecks
  in
    if model.showChecks then
      div [class "checklist-container"]
        [ div [class "checklist"] content ]
    else
      div[][]

view : Model -> Html Msg
view model =
  let
    -- dummy checks for UI testing
    testCheck = [
        Check "CPU Core" "12 cores available" CheckPassed
      , Check "RAM available" "Only 5GB of RAM left" Warning
      , Check "/var usage" "3GB left on /var" Critical
      , Check "Frontend standard" "CSS alignment is terrible, good luck" Warning
      , Check "Networking config" "Port 480 is open" CheckPassed
      , Check "File descriptor limit" "Limited to 10_000" Critical
      , Check "Certificate inspection" "Certificate is up to date" CheckPassed]
  in
  div []
  [
    div [class "header-healthcheck "][]
  , div[class "content-block"]
    [
        displayBigMessage testCheck -- replace `testCheck` by `model.healthcheck`
      , displayCheckListButton model.showChecks
      , checksDisplay model testCheck -- replace `testCheck` by `model.healthcheck`
    ]
  ]
