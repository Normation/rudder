module Hooks.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (attribute, class, href, id)
import Html.Events exposing (onClick)

import Hooks.DataTypes exposing (..)
import Hooks.ViewUtils exposing (..)


view : Model -> Html Msg
view model =
  div[class "d-flex flex-column"]
  [ div [class "callout-fade callout-info"]
    [ div[class "marker"]
      [ span [class "fa fa-info-circle"][]
      ]
    , p[][text "This page shows you the current hooks used in Rudder."]
    , p[][
        text "For more information, please consult the"
      , a [href "/rudder-doc/reference/7.2/usage/advanced_configuration_management.html#_server_event_hooks"][text " documentation."]
      ]
    ]
  , div[class "d-flex"]
    [ div [class "template-sidebar sidebar-left"]
      [ div [id "navbar-scrollspy", class "sidebar-body"]
        [ displayNavList model.categories
        ]
      ]
    , div[ class "template-main"]
      [ div[ attribute "data-bs-spy" "scroll", attribute "data-bs-target" "#navbar-scrollspy", attribute "data-bs-smooth-scroll" "true"]
        [ displayHooksList model.root model.categories
        ]
      ]
    ]
  ]
