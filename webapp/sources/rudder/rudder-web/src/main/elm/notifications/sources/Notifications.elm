port module Notifications exposing (..)

import Html exposing (..)
import Html.Attributes exposing (  class, style, href )
import String
import Toasty
import Browser
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)

port successNotification : (String -> msg) -> Sub msg
port errorNotification   : (String -> msg) -> Sub msg
port warningNotification : (String -> msg) -> Sub msg
port infoNotification    : (String -> msg) -> Sub msg

port linkSuccessNotification : (Value -> msg) -> Sub msg
------------------------------
-- SUBSCRIPTIONS
------------------------------

subscriptions : Model -> Sub Msg
subscriptions _ =
  Sub.batch
    [ successNotification CreateSuccessNotification
    , linkSuccessNotification parseToastInfo
    , errorNotification   CreateErrorNotification
    , warningNotification CreateWarningNotification
    , infoNotification CreateInfoNotification
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
  , toasties     : Toasty.Stack MyToast
  }

type Msg
  = ToastyMsg (Toasty.Msg MyToast)
  | CreateSuccessNotification String
  | CreateLinkSuccessNotification ToastInfo
  | CreateErrorNotification   String
  | CreateWarningNotification String
  | CreateInfoNotification    String

type MyToast =
  Success String | Warning String | Error String | Info String | SuccessLink String String String

type alias ToastInfo =
  { message : String
  , linkUrl : String
  , linkTxt : String
  }

decodeToastInfo : Decoder ToastInfo
decodeToastInfo =
  succeed ToastInfo
    |> required "message" string
    |> required "linkUrl" string
    |> required "linkTxt" string

parseToastInfo: Value -> Msg
parseToastInfo json =
  case Json.Decode.decodeValue decodeToastInfo json of
    Ok toastInfo ->
      CreateLinkSuccessNotification toastInfo
    Err e -> CreateErrorNotification "Error while trying to display notification"

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
    CreateLinkSuccessNotification toastInfo ->
      (model, Cmd.none)
        |> (createLinkSuccessNotification toastInfo)
    CreateErrorNotification m ->
      (model, Cmd.none)
        |> (createErrorNotification m)
    CreateWarningNotification m ->
      (model, Cmd.none)
        |> (createWarningNotification m)
    CreateInfoNotification m ->
      (model, Cmd.none)
        |> (createInfoNotification m)
------------------------------
-- VIEW --
------------------------------

view: Model -> Html Msg
view model =
  div [class "row"]
    [ div[class "toasties"][Toasty.view defaultConfig viewToast ToastyMsg model.toasties]
    ]


------------------------------
-- NOTIFICATIONS --
------------------------------
defaultConfig : Toasty.Config Msg
defaultConfig =
  Toasty.config
    |> Toasty.delay 9999999
    |> Toasty.transitionOutDuration 700
    |> Toasty.transitionOutAttrs [ class "animated fadeOutRightBig", style "max-height" "0", style "margin-top" "0" ]
    |> Toasty.transitionInAttrs [ class "animated bounceInRight" ]
    |> Toasty.containerAttrs  [ class "rudder-notification" ]

tempConfig : Toasty.Config Msg
tempConfig = defaultConfig |> Toasty.delay 3000

addTempToast : MyToast -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
addTempToast toast ( model, cmd ) =
  Toasty.addToast tempConfig ToastyMsg toast ( model, cmd )

addToast : MyToast -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
addToast toast ( model, cmd ) =
  Toasty.addToast defaultConfig ToastyMsg toast ( model, cmd )

createSuccessNotification : String -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
createSuccessNotification message =
  addTempToast (Success  message)

createWarningNotification : String -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
createWarningNotification message =
  addToast (Warning message)

createErrorNotification   : String -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
createErrorNotification   message =
  addToast (Error  message)

createInfoNotification   : String -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
createInfoNotification   message =
  addTempToast (Info  message)

-- NOTIFICATIONS WITH LINK
createLinkSuccessNotification : ToastInfo -> ( Model, Cmd Msg ) -> ( Model, Cmd Msg )
createLinkSuccessNotification toastInfo =
  addToast (SuccessLink toastInfo.message toastInfo.linkUrl toastInfo.linkTxt)

viewToast : MyToast -> Html msg
viewToast toast =
  case toast of
    Success message ->
      genericToast "callout-success" "fa-check-circle" "Success" message
    Warning message ->
      genericToast "callout-warning" "fa-exclamation-circle" "Warning" message
    Error message ->
      genericToast "callout-danger" "fa-times-circle" "Error" message
    Info message ->
      genericToast "callout-info" "fa-info-circle" "Info" message
    SuccessLink message linkUrl linkTxt ->
      linkToast "callout-success" "fa-check-circle" "Success" message linkUrl linkTxt

genericToast : String -> String -> String -> String -> Html msg
genericToast variantClass iconClass title message =
  div
    [ class "callout-fade", class variantClass ]
    [ div [ class "marker"] [ span [ class "fa", class iconClass ] [] ]
    , h4 [ ] [   text title ]
    , p [ ] [ text message ]
    ]

linkToast : String -> String -> String -> String -> String -> String -> Html msg
linkToast variantClass iconClass title message linkUrl linkTxt =
  div
    [ class "callout-fade", class variantClass ]
    [ div [ class "marker"] [ span [ class "fa", class iconClass ] [] ]
    , h4 [] [ text title   ]
    , p  [] [ text message ]
    , a  [ href linkUrl ] [ text linkTxt ]
    ]