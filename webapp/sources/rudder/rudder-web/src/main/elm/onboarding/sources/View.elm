module View exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h3, ul, li, b, label, input, form, a)
import Html.Attributes exposing (class, type_, name, id, href, for, checked, value)
import Html.Attributes.Autocomplete as Autocomplete
import Html.Attributes.Extra exposing (autocomplete)
import Html.Events exposing (onClick, onInput)
import List exposing (any, intersperse, map, sortWith)
import List.Extra exposing (minimumWith)
import String exposing (lines)
import Dict exposing (..)

view : Model -> Html Msg
view model =
  let
    completeClass = "section-complete"
    completeIcon  = "fa fa-check"

    warningClass  = "section-warning"
    warningIcon   = "fas fa-exclamation"

    defaultClass  = "section-default"
    defaultIcon   = "fa fa-info"

    visitedClass  = "section-visited"

    sidebarSection : Section -> Html Msg
    sidebarSection s =
      let
        titleSection =
          case s of
            Welcome          -> "Welcome"
            Account _ _      -> "Account"
            Metrics _ _      -> "Metrics"
            GettingStarted _ -> "Getting Started"

        sectionIndex = case List.Extra.elemIndex s model.sections of
          Just i  -> i
          Nothing -> 0
        activeClass = if sectionIndex == model.activeSection then "activeSection" else ""

        stateClass  = case s of
          Welcome       -> completeClass
          Account se _  ->
            case se of
              Visited   -> visitedClass
              Completed -> completeClass
              Warning   -> warningClass
              Default   -> defaultClass
          Metrics se m  ->
            case se of
              Visited   -> visitedClass
              Completed -> completeClass
              Warning   -> warningClass
              Default   -> defaultClass
          _ -> defaultClass

      in
        li [class (activeClass ++ " " ++ stateClass)]
          [ div [class "timeline-connector"][]
          , div [class "timeline-item", onClick (ChangeActiveSection sectionIndex)]
            [ span [class "item-dot"][]
            , span [class "item-title"][text titleSection]
            ]
          ]

    summaryList : Section -> Html Msg
    summaryList section  =
      let
        (stateClass, iconClass, textItem) = case section of
          Welcome      -> (completeClass , completeIcon , "Rudder is correctly installed.")

          Account s ac ->
            case s of
              Completed -> ( completeClass , completeIcon , "Your account "++ ac.username ++ " has been linked to your Rudder!"  )
              Warning   -> ( warningClass  , warningIcon  , "There is a probleme with your account credentials."       )
              _         -> ( defaultClass  , defaultIcon  , "No account have been linked to your Rudder installation." )

          Metrics s m  ->
            let
              txtMetrics = case m of
                NotDefined -> "No metrics will be shared."
                NoMetrics  -> "No metrics will be shared."
                Minimal    -> "Minimal metrics will be shared anonymously with us, thanks for your help!"
                Complete   -> "Complete metrics will be shared anonymously with us, thanks for your help!"
            in
              case s of
                Completed -> ( completeClass , completeIcon , txtMetrics )
                Warning   -> ( warningClass  , warningIcon  , txtMetrics )
                _         -> ( defaultClass  , defaultIcon  , txtMetrics )

          _ -> ("" , "" , "" )

      in
        li[class stateClass]
          [ span[][i[class iconClass][]]
          , text textItem
          ]

    activeSection : List (Html Msg)
    activeSection =
      case (List.Extra.getAt model.activeSection model.sections) of
        Just s ->
          case s of
            Welcome ->
              [ h3 [] [text "Welcome"]
              , div[]
                [ span[] [text "Rudder installation "]
                , b[ class "text-success" ] [text "is complete"]
                , span[] [text ". Welcome!"]
                ]
              , div[ class "wizard-btn-group"]
                [ button[class "btn btn-default", type_ "button", onClick (GoToLast)] [text "I will configure my Rudder later"]
                , button[class "btn btn-success", type_ "button", onClick (ChangeActiveSection (model.activeSection+1))] [text "Let's configure my account"]
                ]
              ]

            Account state settings ->
              [ h3 [] [text "Account"]
              , div[] [text "Configure your Rudder account for automated plugin download and upgrade."]
              , form[ class "wizard-form", name "wizard-account"]
                [ div [class "form-group"]
                  [ label[] [text "Username"]
                  , input[class "form-control sm-width", type_ "text"    , name "rudder-username", id "rudder-username", value settings.username, onInput (\str -> UpdateSection 1 (Account state { settings | username = str } ))][]
                  ]
                , div [class "form-group"]
                  [ label[] [text "Password"]
                  , input[class "form-control sm-width", type_ "password", name "rudder-password", id "rudder-password", value settings.password, onInput (\str -> UpdateSection 1 (Account state { settings | password = str } ))][]
                  ]
                , div[ class "wizard-btn-group sm-width"]
                  [ button[class "btn btn-success", type_ "button", onClick (ChangeActiveSection (model.activeSection+1))] [text "Continue"] -- "Skip, I will create my account later"
                  ]
                ]
              ]

            Metrics _ metrics ->
              [ h3 [] [text "Metrics"]
              , div[] [text "Help us improve Rudder by providing anonymous usage metrics."]
              , div[]
                [ span[] [text "We take special care of your security and privacy, "]
                , a [href "#"] [text "read more about it on the site"]
                , span[] [text "."]
                ]
              , form [class "wizard-form"]
                [ div [class "checkbox-cards"] 
                  [ div []
                    [ input[type_ "radio", name "metrics", id "no-metrics", onClick (UpdateSection 2 (Metrics Completed NoMetrics )), checked (metrics == NoMetrics)][]
                    , label[for "no-metrics"]
                      [i [class "nothing"][]
                      , span [] [text "Nothing"]
                      ]
                    ]
                  , div []
                    [ input[type_ "radio", name "metrics", id "minimal-metrics", onClick (UpdateSection 2 (Metrics Completed Minimal )), checked (metrics == Minimal)][]
                    , label[for "minimal-metrics"]
                      [i [class "core"][]
                      , span [] [text "Minimal"]
                      ]
                    ]
                  , div []
                    [ input[type_ "radio", name "metrics", id "complete-metrics", onClick (UpdateSection 2 (Metrics Completed Complete )), checked (metrics == Complete)][]
                    , label[for "complete-metrics"]
                      [i [class "tech"][]
                      , span [] [text "Complete"]
                      ]
                    ]
                  ]
                , div[ class "wizard-btn-group sm-width"]
                  [ button[class "btn btn-success", type_ "button", onClick (ChangeActiveSection (model.activeSection+1))] [text "Continue"]
                  ]
                ]
              ]

            GettingStarted _ ->
              let
                listSummary = case List.tail model.sections of
                  Just l  -> l
                  Nothing -> model.sections
              in
                [ h3 [] [text "Getting Started"]
                , ul[class "sections-summary"]
                  (listSummary
                    |> List.map summaryList -- Remove "Getting Started" section from the summary
                  )
                , div[]
                  [ span[] [text "If you are new to Rudder, we advice you to "]
                  , a [href "#"] [text "follow the getting started guide"]
                  , span[] [text "."]
                  ]
                , div[ class "wizard-btn-group"]
                  [ button[class "btn btn-success", type_ "button", onClick SaveAction] [text "Save", i[class "fa fa-save"][]]
                  ]
                ]

        Nothing -> []

  in
    div [class "rudder-template"]
    [ div [class "one-col"]
      [ div [class "one-col-main"]
        [ div [class "template-sidebar sidebar-left"]
          [ div [class "sidebar-body"]
            [ ul[class "wizard-timeline"]
                (model.sections
                  |> List.map sidebarSection)
            ]
          ]
        , div [class "template-main"]
          [ div [class "main-container"]
            [ div [class "main-details"]
              [ div [class "wizard-section"] activeSection ]
            ]
          ]
        ]
      ]
    ]
