port module Onboarding exposing (update)

import Browser
import Browser.Navigation
import DataTypes exposing (..)
import ApiCalls exposing (..)
import Init exposing (init, subscriptions)
import View exposing (view)
import Result
import Process
import Task
import List
import List.Extra

--
-- Port for interacting with external JS
--
port successNotification : String -> Cmd msg
port errorNotification   : String -> Cmd msg

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    ChangeActiveSection index ->
      let
        activeSection = List.Extra.getAt index model.sections
        newSections   = case activeSection of
          Just s  -> case s of
            Account state settings ->
              let
                newState   = if state == Default then Visited else state
                newSection = Account newState settings
              in
                List.Extra.setAt index newSection model.sections

         {-   Metrics state setting  ->
              let
                newState   = if state == Default then Visited else state
                newSection = Metrics newState setting
              in
                List.Extra.setAt index newSection model.sections -}
            _ -> model.sections
          Nothing -> model.sections
      in
        ({model | activeSection = index, sections = newSections, animation = False}, Cmd.none)

    GoToLast ->
      ({model | activeSection = (List.length model.sections) - 1}, setupDone model True)

    UpdateSection index newSection ->
      let
        (newModel, cmd) = case (index, model.animation) of
          (2, False) -> ({model | sections = List.Extra.setAt index newSection model.sections, animation = True}, Process.sleep 500 |> Task.perform (always (ChangeActiveSection 3)))
          (2, True ) -> ( model, Cmd.none )
          _          -> ({model | sections = List.Extra.setAt index newSection model.sections}, Cmd.none)
      in
        (newModel, cmd)

    GetAccountSettings res ->
      case res of
        Ok s ->
          let
            newState    = if String.isEmpty s.username && String.isEmpty s.password then Default else Completed
            newSection  = Account newState (AccountSettings s.username s.password s.url s.proxyUrl s.proxyUser s.proxyPassword)
            newSections = List.Extra.setAt 1 newSection model.sections
            newModel    = {model | sections = newSections}
          in
            (newModel, Cmd.none)
        Err _ ->
          (model, (errorNotification "Error while fetching account credentials"))
{-
    GetMetricsSettings res ->
      case res of
        Ok s ->
          let
            newState    = case s of
              NotDefined -> Default
              _          -> Completed
            newSection  = Metrics newState s
            newSections = List.Extra.setAt 2 newSection model.sections
            newModel    = {model | sections = newSections}
          in
            (newModel, Cmd.none)
        Err _ ->
          (model, (errorNotification "Error while fetching metrics"))
-}
    PostAccountSettings res ->
      let
        flag = case res of
          Ok _    -> True
          Err _ -> False
      in
        ({ model | saveAccountFlag = flag}, setupDone model True)

{-
    PostMetricsSettings res ->
      let
        flag = case res of
          Ok _  -> True
          Err _ -> False
      in
        ({ model | saveMetricsFlag = flag}, setupDone model True)
-}

    SetupDone _ ->
        ( model, Cmd.batch [ actionsAfterSaving model, Task.perform (always Redirect) (Process.sleep 3000) ])

    Redirect ->
        ( model, Browser.Navigation.load (model.contextPath ++ "/secure/index.html"))

    SaveAction ->
      let
        accountSettings = case List.Extra.getAt 1 model.sections of
          Just  s -> case s of
            Account _ settings -> settings
            _ -> AccountSettings "" "" "" Nothing Nothing Nothing
          Nothing -> AccountSettings "" "" "" Nothing Nothing Nothing
        listActions =  [ postAccountSettings model accountSettings ]
      in
        (model, Cmd.batch listActions)

actionsAfterSaving : Model ->  Cmd Msg
actionsAfterSaving model =
  if model.saveAccountFlag == True && model.saveMetricsFlag == True then
    -- SAVING SUCCESS
    successNotification "Your changes have been saved. Redirecting to dashboard in a few seconds ..."
  else
    -- ERROR WHILE SAVING
    let
      errMessage = case (model.saveAccountFlag, model.saveMetricsFlag) of
        (False, True) -> "Error while saving account credentials"
        (True, False) -> "Error while saving metrics"
        _             -> "Error while saving your changes"
    in
      errorNotification errMessage
