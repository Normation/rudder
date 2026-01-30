module Accounts.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onCheck)
import Iso8601
import List
import Accounts.ApiCalls exposing (..)
import Accounts.DataTypes exposing (..)
import Accounts.DatePickerUtils exposing (posixToString, posixOrdering)
import Ordering exposing (Ordering)
import String exposing (slice)
import Time
import Ui.Datatable exposing (thClass, sortTable, SortOrder(..), filterSearch)
import Maybe.Extra


--
-- DATATABLE
--

getSortFunction : Model -> Ordering Account
getSortFunction model =
  let
    order = case model.ui.filters.tableFilters.sortBy of
      Id      -> Ordering.byField .id
      Name    -> Ordering.byField .name
      CreDate -> Ordering.byFieldWith posixOrdering .creationDate
      LstDate -> Ordering.byFieldWith posixOrdering (.lastAuthenticationDate >> Maybe.withDefault (Time.millisToPosix 0))
      ExpDate -> Ordering.byFieldWith posixOrdering (.expirationPolicy >> expirationDate >> Maybe.withDefault (Time.millisToPosix 0))
  in
    if model.ui.filters.tableFilters.sortOrder == Asc then
      order
    else
      Ordering.reverse order

searchField : DatePickerInfo -> Account -> List String
searchField datePickerInfo a =
  List.append [ a.name
  , a.id
  ] ( case a.expirationPolicy of
      ExpireAtDate d  -> [posixToString datePickerInfo.zone d]
      NeverExpire -> []
    )

filterByAuthType : Maybe AuthorizationType -> AuthorizationType -> Bool
filterByAuthType filterAuthType authType =
  filterAuthType
    |> Maybe.map ((==) authType)
    |> Maybe.withDefault True

generateLoadingList : Html Msg
generateLoadingList =
  ul[class "skeleton-loading"]
  [ li[style "width" "calc(100% - 25px)"][i[][], span[][]]
  , li[][i[][], span[][]]
  , li[style "width" "calc(100% - 95px)"][i[][], span[][]]
  , ul[]
    [ li[style "width" "calc(100% - 45px)"][i[][], span[][]]
    , li[style "width" "calc(100% - 125px)"][i[][], span[][]]
    , li[][i[][], span[][]]
    ]
  , li[][i[][], span[][]]
  ]

displayAccountsTable : Model -> Html Msg
displayAccountsTable model =
  let
    trAccount : Account -> Html Msg
    trAccount a =
      let
        inputId = "toggle-" ++ a.id
        expirationDate = case a.expirationPolicy of
          ExpireAtDate d  -> posixToString model.ui.datePickerInfo.zone d
          NeverExpire -> "-"
        lastUsedDate  = case a.lastAuthenticationDate of
          Just d -> posixToString model.ui.datePickerInfo.zone d
          Nothing -> "-"
        tokenExists = case a.tokenGenerationDate of
          Just d -> "✓"
          Nothing -> "-"

      in
        tr[class (if checkIfExpired model.ui.datePickerInfo a then "is-expired" else "")]
        [ td []
        [ text a.name
        , displayAccountDescription a
        , span [class "badge badge-grey"][ text (getAuthorizationType a.authorizationType) ]
        , (if checkIfExpired model.ui.datePickerInfo a then span[class "badge-expired"][] else text "")
        , (if checkIfTokenV1 a then span[class "badge-disabled"][] else text "")
        ]
        , td []
        [ span [class "token-txt"][ text a.id ] ]
        , td [class "date"][ text (posixToString model.ui.datePickerInfo.zone a.creationDate) ]
        , td [class "date"][ text expirationDate ]
        , td [class "date"][ text lastUsedDate ]
        , td
          [ class "date"
          , style "text-align" "right"
          , title ("Generated: " ++ (a.tokenGenerationDate |> Maybe.Extra.unpack (\_ -> "-") (posixToString model.ui.datePickerInfo.zone)))
          ]
          [
            span [style "padding-right" "5px"] [text tokenExists]
          , button
            [ class "btn btn-default reload-token"
            , onClick (ToggleEditPopup (Confirm Regenerate a.name (CallApi (regenerateToken a))))
            ]
            [ span [class "fa fa-repeat"][] ]
          ]
        , td []
          [ button
            [ class "btn btn-default"
            , onClick (ToggleEditPopup (EditAccount a))
            ]
            [ span [class "fa fa-pencil"] [] ]
          , label [for inputId, class "custom-toggle"]
            [ input [type_ "checkbox", id inputId, checked (a.status == Enabled), onCheck (\c -> CallApi (saveAccount {a | status = if c then Enabled else Disabled }))][]
            , label [for inputId, class "custom-toggle-group"]
              [ label [for inputId, class "toggle-enabled" ][text "Enabled"]
              , span  [class "cursor"][]
              , label [for inputId, class "toggle-disabled"][text "Disabled"]
              ]
            ]
          , button
            [ class "btn btn-danger delete-button"
            , onClick (ToggleEditPopup (Confirm Delete a.name (CallApi (deleteAccount a))))
            ]
            [ span [class "fa fa-times-circle"] [] ]
          ]
        ]
    filters = model.ui.filters
    tableFilters = filters.tableFilters
    filteredAccounts = model.accounts
      |> List.filter (\a -> filterSearch tableFilters.filter (searchField model.ui.datePickerInfo a))
      |> List.filter (\a -> filterByAuthType filters.authType a.authorizationType)
      |> List.sortWith (getSortFunction model)
  in
    table [class "dataTable"]
    [ thead []
      [ tr [class "head"]
        [ th [class (thClass tableFilters Name    ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters Name    )})][ text "Account name"]
        , th [class (thClass tableFilters Id      ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters Id      )})][ text "Account id"]
        , th [class (thClass tableFilters CreDate ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters CreDate )})][ text "Created on"]
        , th [class (thClass tableFilters ExpDate ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters ExpDate )})][ text "Expires on" ]
        , th [class (thClass tableFilters LstDate ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters LstDate )})][ text "Last used on"]
        , th [][ text "Token"]
        , th [][ text "Actions" ]
        ]
      ]
    , tbody []
      ( if List.isEmpty model.accounts then
        [ tr[]
          [ td[class "empty", colspan 7][i [class"fa fa-exclamation-triangle"][], text "There are no API accounts defined"] ]
        ]
      else if List.isEmpty filteredAccounts then
        [ tr[]
          [ td[class "empty", colspan 7][i [class"fa fa-exclamation-triangle"][], text "No API accounts match your filters"] ]
        ]
      else
        List.map (\a -> trAccount a) filteredAccounts
      )
    ]

getAuthorizationType : AuthorizationType -> String
getAuthorizationType authorizationKind =
  case authorizationKind of
    RO   -> "Read only"
    RW   -> "Full access"
    None -> "No access"
    ACL  -> "Custom ACLs"

displayAccountDescription : Account -> Html Msg
displayAccountDescription a =
  if String.isEmpty a.description then
    text ""
  else
    span
      [ class "fa fa-question-circle icon-info"
      , attribute "data-bs-toggle" "tooltip"
      , attribute "data-bs-placement" "top"
      , attribute "title" (buildTooltipContent "Description" a.description)
      ][]


-- WARNING:
--
-- Here we are building an html snippet that will be placed inside an attribute, so
-- we can't easily use the Html type as there is no built-in way to serialize it manually.
-- This means it will be vulnerable to XSS on its parameters (here the description).
--
-- We resort to escaping it manually here.
buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
    escapedTitle = htmlEscape title
    escapedContent = htmlEscape content
  in
    headingTag ++ escapedTitle ++ contentTag ++ escapedContent ++ closeTag

htmlEscape : String -> String
htmlEscape s =
  String.replace "&" "&amp;" s
    |> String.replace ">" "&gt;"
    |> String.replace "<" "&lt;"
    |> String.replace "\"" "&quot;"
    |> String.replace "'" "&#x27;"
    |> String.replace "\\" "&#x2F;"

exposeToken : Maybe Token -> String
exposeToken t =
  case t of
    Just (New s) -> s
    _            -> ""
