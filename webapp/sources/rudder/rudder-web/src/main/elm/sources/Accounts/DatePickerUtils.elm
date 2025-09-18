module Accounts.DatePickerUtils exposing (..)

import SingleDatePicker exposing (Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings)
import Time exposing (Month(..), Posix, Zone)
import Time.Extra as Time exposing (Interval(..), Parts)
import Accounts.DataTypes exposing (..)
import Accounts.DataTypes as TokenState exposing (..)


isDateBeforeToday : Posix -> Posix -> Bool
isDateBeforeToday today datetime =
  Time.posixToMillis today > Time.posixToMillis datetime

userDefinedDatePickerSettings : Zone -> Posix -> Posix -> Settings
userDefinedDatePickerSettings zone today focusDate =
  let
    defaults = defaultSettings zone
  in
    { defaults
    | isDayDisabled = \clientZone datetime -> isDateBeforeToday (Time.floor Day clientZone today) datetime
    , focusedDate = Just focusDate
    , dateStringFn = posixToDateString
    , timePickerVisibility =
      AlwaysVisible
      { defaultTimePickerSettings
      | timeStringFn = posixToTimeString
      , allowedTimesOfDay = \clientZone datetime -> adjustAllowedTimesOfDayToClientZone zone clientZone today datetime
      }
    }

addLeadingZero : Int -> String
addLeadingZero value =
  let
    string = String.fromInt value
  in
    if String.length string == 1 then "0" ++ string else string

monthToNmbString : Month -> String
monthToNmbString month =
  case month of
    Jan -> "01"
    Feb -> "02"
    Mar -> "03"
    Apr -> "04"
    May -> "05"
    Jun -> "06"
    Jul -> "07"
    Aug -> "08"
    Sep -> "09"
    Oct -> "10"
    Nov -> "11"
    Dec -> "12"

adjustAllowedTimesOfDayToClientZone : Zone -> Zone -> Posix -> Posix -> { startHour : Int, startMinute : Int, endHour : Int, endMinute : Int }
adjustAllowedTimesOfDayToClientZone baseZone clientZone today datetimeBeingProcessed =
  let
    processingPartsInClientZone   = Time.posixToParts clientZone datetimeBeingProcessed
    todayPartsInClientZone        = Time.posixToParts clientZone today

    startPartsAdjustedForBaseZone = Time.posixToParts baseZone datetimeBeingProcessed
      |> (\parts -> Time.partsToPosix baseZone { parts | hour = 0, minute = 0 })
      |> Time.posixToParts clientZone

    endPartsAdjustedForBaseZone = Time.posixToParts baseZone datetimeBeingProcessed
      |> (\parts -> Time.partsToPosix baseZone { parts | hour = 23, minute = 59 })
      |> Time.posixToParts clientZone

    bounds =
      { startHour   = startPartsAdjustedForBaseZone.hour
      , startMinute = startPartsAdjustedForBaseZone.minute
      , endHour     = endPartsAdjustedForBaseZone.hour
      , endMinute   = endPartsAdjustedForBaseZone.minute
      }
  in
    if processingPartsInClientZone.day == todayPartsInClientZone.day && processingPartsInClientZone.month == todayPartsInClientZone.month && processingPartsInClientZone.year == todayPartsInClientZone.year then
      if todayPartsInClientZone.hour > bounds.startHour || (todayPartsInClientZone.hour == bounds.startHour && todayPartsInClientZone.minute > bounds.startMinute) then
        { startHour = todayPartsInClientZone.hour, startMinute = todayPartsInClientZone.minute, endHour = bounds.endHour, endMinute = bounds.endMinute }
      else
        bounds
    else
      bounds

posixToDateString : Zone -> Posix -> String
posixToDateString zone date =
  addLeadingZero (Time.toYear zone date)
    ++ "-"
    ++ monthToNmbString (Time.toMonth zone date)
    ++ "-"
    ++ addLeadingZero (Time.toDay zone date)

posixToTimeString : Zone -> Posix -> String
posixToTimeString zone datetime =
  addLeadingZero (Time.toHour zone datetime)
    ++ ":"
    ++ addLeadingZero (Time.toMinute zone datetime)

getDateString : DatePickerInfo -> Maybe Posix -> String
getDateString datePickerInfo pickedTime =
  case pickedTime of
    Just d  ->
      posixToString datePickerInfo d
    Nothing ->
      ""

posixToString : DatePickerInfo -> Posix -> String
posixToString datePickerInfo p =
  (posixToDateString datePickerInfo.zone p ++ " " ++ posixToTimeString datePickerInfo.zone p)

checkIfExpired : DatePickerInfo -> Account -> Bool
checkIfExpired datePickerInfo account =
  case account.expirationPolicy of
    ExpireAtDate p -> isDateBeforeToday datePickerInfo.currentTime p
    NeverExpire -> False

checkIfTokenV1 : Account -> Bool
checkIfTokenV1 a =
  case a.tokenState of
    TokenState.GeneratedV1 -> True
    TokenState.GeneratedV2 -> False
    TokenState.Undef       -> False
