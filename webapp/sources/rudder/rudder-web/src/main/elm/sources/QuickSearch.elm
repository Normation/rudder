module QuickSearch exposing (..)

import Browser
import QuickSearch.Init exposing (..)
import QuickSearch.Update exposing (update)
import QuickSearch.View exposing (view)

main = Browser.element
  { init          = init
  , view          = view
  , update        = update
  , subscriptions = subscriptions
  }
