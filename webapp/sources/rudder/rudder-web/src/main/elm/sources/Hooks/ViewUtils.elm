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
          [ h4[id prefix][text title]
          , ul[class "category-sublist"]( cat
            |> List.map (\c ->
              let
                hasSublist = not (List.isEmpty c.hooks)
                subClass = if hasSublist then "sublist" else ""
              in
                li[]
                [ h5[id c.name, class subClass] [ text (humanize (String.replace prefix "" c.name)) ]
                , ( if hasSublist then
                  ul[class "hooks-sublist"]
                  ( List.map (\h -> li[][text h.name]) c.hooks )
                  else
                  text ""
                  )
                ]
              )
            )
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
          li [class "nav-item"]
          [ a [class "nav-link", href ( "#" ++ prefix )]
            [ text title
            ]
          , ul[class "nav nav-tabs"] (
            List.map (\c -> li[class "nav-item"]
              [ a [class "nav-link", href ( "#" ++ c.name )]
                [ text (humanize (String.replace prefix "" c.name))
                ]
              ]
            ) cat)
          ]
  in
    ul[class "nav nav-tabs"](List.map (\k -> displayNavItem k) kindList)