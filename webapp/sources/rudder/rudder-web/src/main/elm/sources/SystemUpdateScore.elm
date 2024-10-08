port module SystemUpdateScore exposing (..)

import Browser
import Html
import Html.String exposing (..)
import Html.String.Attributes exposing (class, title, attribute, style)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import String.Extra

import Compliance.Utils exposing (buildTooltipContent)

port sendHtml : String -> Cmd msg
port getValue : (Value -> msg) -> Sub msg

type Msg = NewScore Value

type alias SystemUpdateStats =
  { nbPackages : Int,
    security : Maybe Int,
    patch : Maybe Int,
    defect : Maybe Int,
    enhancement : Maybe Int,
    other : Maybe Int
  }

decodeSystemUpdateStats : Decoder SystemUpdateStats
decodeSystemUpdateStats =
  succeed SystemUpdateStats
    |> required "nbPackages" int
    |> optional "security" (maybe int) Nothing
    |> optional "updates" (maybe int) Nothing
    |> optional "defect" (maybe int) Nothing
    |> optional "enhancement" (maybe int) Nothing
    |> optional "other" (maybe int) Nothing

buildScoreDetails :  SystemUpdateStats  -> Html msg
buildScoreDetails details =
  let
    toBadge : String -> String -> Maybe Int -> Html msg
    toBadge id iconClass value =
      case value of
        Just v  ->
          let
            valueTxt = String.fromInt v
            titleTxt = "<b>" ++ (String.Extra.humanize id) ++ ":</b> " ++ valueTxt
          in
            span
            [ class ("badge badge-type " ++ id)
            , attribute "data-bs-toggle" "tooltip"
            , attribute "data-bs-placement" "top"
            , title (buildTooltipContent "System updates" titleTxt)
            ]
            [ i[class ("fa fa-" ++ iconClass)][], text (" " ++ valueTxt) ]
        Nothing -> text ""

  in
    if(details.nbPackages == 0) then
      div[]
      [ span
        [ class "badge badge-type up-to-date"
        , attribute "data-bs-toggle" "tooltip"
        , attribute "data-bs-placement" "top"
        , title (buildTooltipContent "System updates" "Everything is up-to-date!")
        ]
        [ i[class "fa fa-check-circle", style "margin-right" "0px"][]
        ]
      , text ""
      , text ""
      , text ""
      ]
    else
      div[]
        [ toBadge "security"    "warning"      details.security
        , toBadge "bugfix"      "bug"          details.defect
        , toBadge "enhancement" "plus"         details.enhancement
        , toBadge "update"      "box"          details.patch
        ]

main =
  Browser.element
    { init = init
    , view = always (Html.text "")
    , update = update
    , subscriptions = subscriptions
    }


-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg
subscriptions :  () -> Sub Msg
subscriptions _ = getValue (NewScore)

init : () -> ( (), Cmd Msg )
init _ = ( (), Cmd.none )

update :  Msg -> () -> ( () , Cmd Msg)
update msg model =
    case msg of
        NewScore value ->
          case (Json.Decode.decodeValue decodeSystemUpdateStats value) of
            Ok compliance ->
              let
                cmd = buildScoreDetails compliance |> Html.String.toString 0 |> sendHtml
              in
                (model, cmd)
            Err err ->
               (model, errorNotification(("Error while reading system update score details, error is:" ++ (errorToString err))))