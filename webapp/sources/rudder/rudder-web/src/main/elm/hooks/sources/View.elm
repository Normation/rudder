module View exposing (..)

import DataTypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (attribute, class, href, id)
import Html.Events exposing (onClick)
import ViewUtils exposing (..)


view : Model -> Html Msg
view model =
  div[ class "rudder-template"]
  [ div[ class "one-col"]
    [ div[ class "main-header"]
      [ div[ class "header-title"]
        [ h1[]
          [ span[] [text "Hooks"]
          ]
        ]
      , div [class "header-description"]
        [ p[][text "This page shows you the current hooks used in Rudder"]
        ]
      ]
    , div[ class "one-col-main"]
      [ div [class "template-sidebar sidebar-left"]
        [ div [id "navbar-scrollspy", class "sidebar-body"]
          [ displayNavList model.categories
          ]
        ]
      , div[ class "template-main"]
        [ div[ class "main-container"]
          [ div[ class "main-details", attribute "data-spy" "scroll", attribute "data-target" "#navbar-scrollspy"]
            [ displayHooksList model.root model.categories
            ]
          ]
        ]
      ]
    ]
  ]
