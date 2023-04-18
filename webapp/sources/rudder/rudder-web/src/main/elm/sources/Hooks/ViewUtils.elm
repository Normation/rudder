module Hooks.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick)
import List
import Json.Decode as Decode
import NaturalOrdering as N exposing (compare)
import String.Extra exposing (humanize)

import Hooks.ApiCalls exposing (..)
import Hooks.DataTypes exposing (..)


kindList = [Node, Policy, Other]

displayHooksList : String ->  List Category -> Html Msg
displayHooksList root categories =
  let
    displayCategory : Kind -> Html Msg
    displayCategory kind =
      let
        (prefix, title) = case kind of
          Node   -> ( "node"   , "Node"   )
          Policy -> ( "policy" , "Policy" )
          Other  -> ( ""       , "Other"  )

        cat = List.filter (\c -> c.kind == kind) categories
      in
        if List.isEmpty cat then
          text ""
        else
          li[]
          [ h3[id prefix][text title]
          , ul[class "category-sublist"](List.map (\c -> li[]
            [ h4[id c.name] [ text (humanize (String.replace prefix "" c.name)) ]
            , ( if List.isEmpty c.hooks then
              text ""
            else
              ul[class "hooks-sublist"]
              ( List.map (\h -> li[][text h.name]) c.hooks )
            )
            ]
            ) cat)
          ]
  in
    ul[class "hooks-list col-lg-8"](List.map (\k -> displayCategory k) kindList)

displayNavList : List Category -> Html Msg
displayNavList categories =
  let
    displayNavItem : Kind -> Html Msg
    displayNavItem kind =
      let
        (prefix, title) = case kind of
          Node   -> ( "node"   , "Node"   )
          Policy -> ( "policy" , "Policy" )
          Other  -> ( ""       , "Other"  )

        cat = List.filter (\c -> c.kind == kind) categories
      in
        if List.isEmpty cat then
          text ""
        else
          li [class "ui-tabs-tab"]
          [ a [href ( "#" ++ prefix )]
            [ text title
            ]
          , ul[class "nav nav-tabs"] (
            List.map (\c -> li[]
              [ a [href ( "#" ++ c.name )]
                [ text (humanize (String.replace prefix "" c.name))
                ]
              ]
            ) cat)
          ]
  in
    ul[class "nav nav-tabs"](List.map (\k -> displayNavItem k) kindList)