port module Agentschedule exposing (..)

import Browser
import Http exposing (..)
import Result
import Json.Decode exposing (Value)

import Agentschedule.DataTypes exposing (..)
import Agentschedule.Init exposing (init)
import Agentschedule.JsonEncoder exposing (encodeSchedule)
import Agentschedule.View exposing (view)
import Agentschedule.ViewUtils exposing (hours, minutes)


-- PORTS / SUBSCRIPTIONS
port saveSchedule : Value  -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions _ =
  Sub.none

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    Ignore ->
      ( model , Cmd.none)

    SaveChanges schedule ->
      let
        ui = model.ui
        newModel = {model | currentSettings = Just schedule}
      in
      ( newModel , (saveSchedule (encodeSchedule schedule)))

    UpdateSchedule schedule ->
      let
        checkValue : Int -> List Int -> Int
        checkValue val list =
          if not (List.member val list) then
            Maybe.withDefault 0 (List.head list)
          else
            val
        newSchedule = { schedule
          | startHour   = checkValue schedule.startHour (hours schedule)
          , splayHour   = checkValue schedule.splayHour (hours schedule)
          , startMinute = checkValue schedule.startMinute (minutes schedule)
          , splayMinute = checkValue schedule.splayMinute (minutes schedule)
          }
      in
        ({model | selectedSettings = Just newSchedule}, Cmd.none)
