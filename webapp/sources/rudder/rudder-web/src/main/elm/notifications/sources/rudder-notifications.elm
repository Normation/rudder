port module RudderNotifications exposing (..)

import Html exposing (..)
import Html.Attributes exposing ( style, class, type_, checked )
import Html.Events exposing (..)
import String
import Toasty
import Toasty.Defaults
import Http exposing (Error)
import Http exposing (..)
import Json.Encode as E
import Browser


port successNotification : (String -> msg) -> Sub msg
port errorNotification   : (String -> msg) -> Sub msg
port warningNotification : (String -> msg) -> Sub msg

------------------------------
-- SUBSCRIPTIONS
------------------------------

subscriptions : Model -> Sub Msg
subscriptions model =
  Sub.batch
    [ successNotification CreateSuccessNotification
    , errorNotification   CreateErrorNotification
    , warningNotification CreateWarningNotification
    ]

------------------------------
-- Init and main --
------------------------------

init : { contextPath: String } -> (Model, Cmd Msg)
init flags =
  let
    initModel = Model flags.contextPath Nothing Toasty.initialState
  in
    (initModel, Cmd.none)

main = Browser.element
  { init = init
  , view = view
  , update = update
  , subscriptions = subscriptions
  }


------------------------------
-- MODEL --
------------------------------

type alias Model =
  { contextPath  : String
  , message      : Maybe String
  , toasties     : Toasty.Stack Toasty.Defaults.Toast
  }

type Msg
  = ToastyMsg (Toasty.Msg Toasty.Defaults.Toast)
  | CreateSuccessNotification String
  | CreateErrorNotification   String
  | CreateWarningNotification String


------------------------------
-- UPDATE --
------------------------------

update : Msg -> Model -> (Model, Cmd Msg)
update msg model =
  case msg of
    ToastyMsg m ->
      Toasty.update defaultConfig ToastyMsg m model

    CreateSuccessNotification m ->
      (model, Cmd.none)
        |> (createSuccessNotification m)

    CreateErrorNotification m ->
      (model, Cmd.none)
        |> (createErrorNotification m)

    CreateWarningNotification m ->
      (model, Cmd.none)
        |> (createWarningNotification m)
------------------------------
-- VIEW --
------------------------------

view: Model -> Html Msg
view model =
  div [class "row"]
    [ div[class "toasties"][Toasty.view defaultConfig Toasty.Defaults.view ToastyMsg model.toasties]
    ]


------------------------------
-- NOTIFICATIONS --
------------------------------
defaultConfig : Toasty.Config Msg
defaultConfig =
  Toasty.Defaults.config
    |> Toasty.delay 9999999
    |> Toasty.containerAttrs
    [ class "rudder-notification"
    ]

tempConfig : Toasty.Config Msg
tempConfig = defaultConfig |> Toasty.delay 3000

addTempToast : Toasty.Defaults.Toast -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
addTempToast toast ( model, cmd ) =
  Toasty.addToast tempConfig ToastyMsg toast ( model, cmd )

addToast : Toasty.Defaults.Toast -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
addToast toast ( model, cmd ) =
  Toasty.addToast defaultConfig ToastyMsg toast ( model, cmd )

createSuccessNotification : String -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
createSuccessNotification message =
  addTempToast (Toasty.Defaults.Success "Success!" message)

createWarningNotification : String -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
createWarningNotification message =
  addToast (Toasty.Defaults.Warning "Warning:" message)

createErrorNotification   : String -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
createErrorNotification   message =
  addToast (Toasty.Defaults.Error "Error..." message)
