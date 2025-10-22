module Accounts.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onCheck)
import List
import NaturalOrdering as N exposing (compare)
import Accounts.ApiCalls exposing (..)
import Accounts.DataTypes exposing (..)
import Accounts.DatePickerUtils exposing (checkIfExpired, checkIfTokenV1, posixToString)
import String exposing (slice)
import Ui.Datatable exposing (thClass, sortTable, SortOrder(..), filterSearch)
import Maybe.Extra


--
-- DATATABLE
--

getSortFunction : Model -> Account -> Account -> Order
getSortFunction model a1 a2 =
  let
    datePickerInfo = model.ui.datePickerInfo
    order = case model.ui.filters.tableFilters.sortBy of
      Id      -> N.compare a1.id a2.id
      ExpDate ->
        let
          expDate1 = case expirationDate a1.expirationPolicy of
            Just d  -> (posixToString datePickerInfo d)
            Nothing -> ""
          expDate2 = case expirationDate a2.expirationPolicy of
            Just d  -> (posixToString datePickerInfo d)
            Nothing -> ""
        in
          N.compare expDate1 expDate2
      CreDate -> N.compare a1.creationDate a2.creationDate
      _       -> N.compare a1.name a2.name
  in
    if model.ui.filters.tableFilters.sortOrder == Asc then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

searchField : DatePickerInfo -> Account -> List String
searchField datePickerInfo a =
  List.append [ a.name
  , a.id
  ] ( case a.expirationPolicy of
      ExpireAtDate d  -> [posixToString datePickerInfo d]
      NeverExpire -> []
    )

cleanDate: String -> String
cleanDate date = slice 0 16 (String.replace "T" " " date)

filterByAuthType : String -> String -> Bool
filterByAuthType filterAuthType authType=
  String.isEmpty filterAuthType || filterAuthType == authType

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
          ExpireAtDate d  -> posixToString model.ui.datePickerInfo d
          NeverExpire -> "Never"
        tokenExists = case a.tokenGenerationDate of
          Just d -> "✓"
          Nothing -> "-"

      in
        tr[class (if checkIfExpired model.ui.datePickerInfo a then "is-expired" else "")]
        [ td []
        [ text a.name
        , displayAccountDescription a
        , span [class "badge badge-grey"][ text (getAuthorisationType a.authorisationType) ]
        , (if checkIfExpired model.ui.datePickerInfo a then span[class "badge-expired"][] else text "")
        , (if checkIfTokenV1 a then span[class "badge-disabled"][] else text "")
        ]
        , td []
        [ span [class "token-txt"][ text a.id ] ]
        , td [class "date"][ text (cleanDate a.creationDate) ]
        , td [class "date"][ text expirationDate ]
        , td
          [ class "date"
          , style "text-align" "right"
          , title ("Generated: " ++ (a.tokenGenerationDate |> Maybe.Extra.unpack (\_ -> "-") cleanDate))
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
            [ input [type_ "checkbox", id inputId, checked a.enabled, onCheck (\c -> CallApi (saveAccount {a | enabled = c}))][]
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
      |> List.filter (\a -> filterByAuthType filters.authType a.authorisationType)
      |> List.sortWith (getSortFunction model)
  in
    table [class "dataTable"]
    [ thead []
      [ tr [class "head"]
        [ th [class (thClass tableFilters Name    ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters Name    )})][ text "Account name"]
        , th [class (thClass tableFilters Id      ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters Id      )})][ text "Account id"]
        , th [class (thClass tableFilters CreDate ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters CreDate )})][ text "Creation date"]
        , th [class (thClass tableFilters ExpDate ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters ExpDate )})][ text "Expiration date" ]
        , th [class (thClass tableFilters TknDate ), onClick (UpdateFilters {filters | tableFilters = (sortTable tableFilters TknDate )})][ text "Token"]
        , th [][ text "Actions" ]
        ]
      ]
    , tbody []
      ( if List.isEmpty model.accounts then
        [ tr[]
          [ td[class "empty", colspan 4][i [class"fa fa-exclamation-triangle"][], text "There are no API accounts defined"] ]
        ]
      else if List.isEmpty filteredAccounts then
        [ tr[]
          [ td[class "empty", colspan 4][i [class"fa fa-exclamation-triangle"][], text "No API accounts match your filters"] ]
        ]
      else
        List.map (\a -> trAccount a) filteredAccounts
      )
    ]

getAuthorisationType : String -> String
getAuthorisationType authorizationKind =
  case authorizationKind of
  "ro"   -> "Read only"
  "rw"   -> "Full access"
  "none" -> "No access"
  "acl"  -> "Custom ACLs"
  _ -> authorizationKind

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
