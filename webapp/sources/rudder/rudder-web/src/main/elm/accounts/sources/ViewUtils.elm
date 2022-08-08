module ViewUtils exposing (..)

import DataTypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput, custom, onCheck)
import List
import Json.Decode as Decode
import NaturalOrdering as N exposing (compare)
import ApiCalls exposing (..)
import DatePickerUtils exposing (posixToString, checkIfExpired)
--
-- DATATABLE
--

thClass : TableFilters -> SortBy -> String
thClass tableFilters sortBy =
  if sortBy == tableFilters.sortBy then
    case  tableFilters.sortOrder of
      Asc  -> "sorting_asc"
      Desc -> "sorting_desc"
  else
    "sorting"

sortTable : TableFilters -> SortBy -> TableFilters
sortTable tableFilters sortBy =
  let
    order =
      case tableFilters.sortOrder of
        Asc -> Desc
        Desc -> Asc
  in
    if sortBy == tableFilters.sortBy then
      { tableFilters | sortOrder = order}
    else
      { tableFilters | sortBy = sortBy, sortOrder = Asc}


getSortFunction : Model -> Account -> Account -> Order
getSortFunction model a1 a2 =
  let
    datePickerInfo = model.ui.datePickerInfo
    order = case model.ui.tableFilters.sortBy of
      Name    -> N.compare a1.name a2.name
      Token   -> N.compare a1.token a2.token
      ExpDate ->
        let
          expDate1 = case a1.expirationDate of
            Just d  -> (posixToString datePickerInfo d)
            Nothing -> ""
          expDate2 = case a2.expirationDate of
            Just d  -> (posixToString datePickerInfo d)
            Nothing -> ""
        in
          N.compare expDate1 expDate2
  in
    if model.ui.tableFilters.sortOrder == Asc then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

searchField datePickerInfo a =
  List.append [ a.name
  , a.token
  ] ( case a.expirationDate of
      Just d  -> [posixToString datePickerInfo d]
      Nothing -> []
    )

filterSearch : String -> List String -> Bool
filterSearch filterString searchFields =
  let
    -- Join all the fields into one string to simplify the search
    stringToCheck = searchFields
      |> String.join "|"
      |> String.toLower

    searchString  = filterString
      |> String.toLower
      |> String.trim
  in
    String.contains searchString stringToCheck

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
        expirationDate = case a.expirationDate of
          Just d  -> if a.expirationDateDefined then (posixToString model.ui.datePickerInfo d) else "Never"
          Nothing -> "Never"

      in
        tr[class (if checkIfExpired model.ui.datePickerInfo a then "is-expired" else "")]
        [ td []
        [ text a.name
        , displayAccountDescription a
        , span [class "badge badge-grey"][ text (getAuthorisationType a.authorisationType) ]
        , (if checkIfExpired model.ui.datePickerInfo a then span[class "badge-expired"][] else text "")
        ]
        , td [class "token"]
          [ button [class "btn btn-default reload-token", onClick (ToggleEditPopup (Confirm Regenerate a.name (CallApi (regenerateToken a))))]
            [ span [class "fa fa-repeat"][] ]
          , span [class "token-txt"]
            [text (String.slice  0  5 a.token)
            , span[class "fa hide-text"][]
            ]
          , Html.a [ class "btn-goto clipboard", title "Copy to clipboard" , onClick (Copy a.token) ]
            [ i [class "ion ion-clipboard"][] ]
          ]
        , td [class "date"][ text expirationDate ]
        , td []
          [ button [class "btn btn-default", onClick (ToggleEditPopup (EditAccount a))] [span [class "fa fa-pencil"] [] ]
          , label [for inputId, class "custom-toggle"]
            [ input [type_ "checkbox", id inputId, checked a.enabled, onCheck (\c -> CallApi (saveAccount {a | enabled = c}))][]
            , label [for inputId, class "custom-toggle-group"]
              [ label [for inputId, class "toggle-enabled" ][text "Enabled"]
              , span  [class "cursor"][]
              , label [for inputId, class "toggle-disabled"][text "Disabled"]
              ]
            ]
          , button [class "btn btn-danger delete-button", onClick (ToggleEditPopup (Confirm Delete a.name (CallApi (deleteAccount a))))] [span [class "fa fa-times-circle"] [] ]
          ]
        ]
    filters = model.ui.tableFilters
    filteredAccounts = model.accounts
      |> List.filter (\a -> filterSearch model.ui.tableFilters.filter (searchField model.ui.datePickerInfo a))
      |> List.filter (\a -> filterByAuthType model.ui.tableFilters.authType a.authorisationType)
      |> List.sortWith (getSortFunction model)
  in
    table [class "dataTable"]
    [ thead []
      [ tr [class "head"]
        [ th [class (thClass model.ui.tableFilters Name    ), onClick (UpdateTableFilters (sortTable filters Name    ))][ text "Account name"    ]
        , th [class (thClass model.ui.tableFilters Token   ), onClick (UpdateTableFilters (sortTable filters Token   ))][ text "Token"           ]
        , th [class (thClass model.ui.tableFilters ExpDate ), onClick (UpdateTableFilters (sortTable filters ExpDate ))][ text "Expiration date" ]
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
          [ td[class "empty", colspan 4][i [class"fa fa-exclamation-triangle"][], text "No api accounts match your filters"] ]
        ]
      else
        List.map trAccount filteredAccounts
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
      [ class "bs-tooltip fa fa-question-circle icon-info"
      , attribute "data-toggle" "tooltip"
      , attribute "data-placement" "top"
      , attribute "data-container" "body"
      , attribute "data-html" "true"
      , attribute "data-original-title" (buildTooltipContent "Description" a.description)
      ][ i[class "fa fa-"][] ]


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
