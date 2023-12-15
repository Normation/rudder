module View exposing (..)

import DataTypes exposing (Check, Model, Msg(..), SeverityLevel(..))
import Html exposing (Html, br, div, i, span, text)
import Html.Attributes exposing (class)
import List exposing (any, intersperse, map, sortWith)
import List.Extra exposing (minimumWith)
import String exposing (lines)

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
          Critical    -> text "Some elements require your attention"
          Warning     -> text "Some elements require your attention"
          CheckPassed -> text "All checks passed"
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
               msgCheck = intersperse (br [][]) (map text  (lines c.msg))
             in
               div [class "check"]
               (msgCheck ++ [span [class classNameCircle][]])

     ) sortedChecks
  in
    div [class "checklist-container"]
      [ div [class "checklist"] content ]

view : Model -> Html Msg
view model =
  div []
  [
    div [class "header-healthcheck "][]
  , div[class "content-block"]
    [
        displayBigMessage model.healthcheck
      , checksDisplay model model.healthcheck
    ]
  ]
