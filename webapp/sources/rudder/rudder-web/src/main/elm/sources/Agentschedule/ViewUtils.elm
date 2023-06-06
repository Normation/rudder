module Agentschedule.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (id, class, href, type_, attribute, disabled, for, checked, selected, value)
import Html.Events exposing (onClick, onInput)
import Maybe.Extra exposing (isJust)
import Dict exposing (Dict)

import Agentschedule.DataTypes exposing (..)


intervals : Dict Int String
intervals = Dict.fromList
  [ (5  , "5 minutes" )
  , (10 , "10 minutes")
  , (15 , "15 minutes")
  , (20 , "20 minutes")
  , (30 , "30 minutes")
  , (60 , "hour"      )
  , (120, "2 hours"   )
  , (240, "4 hours"   )
  , (360, "6 hours"   )
  ]

hours : Schedule -> List Int
hours s = List.range 0 (clamp 0 5 ((s.interval // 60) - 1))

minutes : Schedule -> List Int
minutes s = List.range 0 (clamp 0 59 s.interval)

getIntervalValue : Int -> String
getIntervalValue runInterval =
  case Dict.get runInterval intervals of
    Just i  -> i
    Nothing -> "(incorrect interval)"

format2Digits : Int -> String
format2Digits number =
  let
    strNum = ("0" ++ (String.fromInt number))
    endIndex = (String.length strNum)
  in
    String.slice -2 endIndex strNum