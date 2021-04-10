module View exposing (..)
import DataTypes exposing (Column(..), Mode(..), Model, Msg(..), Secret, SecretInfo, Sorting(..), StateInput(..))
import Html exposing (Html, b, button, div, h1, h4, i, input, label, span, table, tbody, td, text, textarea, th, thead, tr, ul)
import Html.Attributes exposing (attribute, class, colspan, disabled, id, placeholder, tabindex, type_)
import Html.Events exposing (onClick, onInput)
import List exposing (concat, isEmpty, map, member)
import Markdown
import String exposing (left)

constructTableLine : List SecretInfo -> List String -> Html Msg
constructTableLine secrets openedDescription =
  let
    lines =
      map (
        \s ->
        let
          isFocused = if (member s.name openedDescription) then "focused" else "secretLine"
          chevronSide = if (member s.name openedDescription) then "fa-chevron-down" else "fa-chevron-right"
        in
        [
          tr [ class ("curspoint " ++ isFocused), attribute "role" "row", onClick (OpenDescription s) ]
          [
            td [class "name-content"][i [class ("chevron fas fa-xs " ++ chevronSide)][],b[][text s.name]]
          , td [class "description-content"][text (left 250 s.description)]
          , td [class "change-content"]
            [
                button [class "btn-edit btn btn-default btn-sm", onClick (OpenModal Edit (Just s))] [text "Edit"]
              , button [class "btn-del btn btn-danger btn-sm", onClick (OpenModal Delete (Just s))] [text "Delete"]

            ]
          ]
          , if isEmpty openedDescription then
              div [][]
            else if (member s.name openedDescription) then
              displayDescriptionDetails s
            else
              div [][]
          ]
     ) secrets
 in
 tbody [] (concat lines)

displayTable : Model -> Html Msg
displayTable model =
  let
    secretsToDisplay = case model.filteredSecrets of
      Just filteredSec -> filteredSec
      Nothing -> model.secrets
    sortingArrowName = case model.sortOn of
      (sortType, Name) ->
        case sortType of
          ASC -> i [class "sort-arrow fas fa-sort-down fa-xs"] []
          DESC -> i [class "sort-arrow fas fa-sort-up fa-xs"] []
          NONE -> i [] []
      _ ->
        i [][]
    sortingArrowDesc = case model.sortOn of
      (sortType, Description)->
        case sortType of
          ASC -> i [class "sort-arrow fas fa-sort-down fa-xs"] []
          DESC -> i [class "sort-arrow fas fa-sort-up fa-xs"] []
          NONE -> i[][]
      _ ->
        i [][]
    isColoredTitleColName = case model.sortOn of
      (DESC, Name)     -> "title-col-colored"
      (ASC, Name)      -> "title-col-colored"
      (NONE, _)        -> ""
      (_, Description) -> ""
    isColoredTitleColDesc = case model.sortOn of
      (DESC, Description)-> "title-col-colored"
      (ASC, Description) -> "title-col-colored"
      (NONE, _)          -> ""
      (_, Name)          -> ""
  in
  div [id "secretsGrid_wrapper", class "dataTables_wrapper no-footer"]
  [
    div [class "dataTables_wrapper_top"]
    [
      div [id "secretsGrid_filter", class "dataTables_filter"]
      [
        label []
        [
          input [type_ "search", placeholder "Filter", attribute "aria-controls" "secretsGrid", onInput FilterSecrets][]
        ]
      ]
    ]
    , table [id "secretsGrid", class "dataTable no-footer" ]
      [
        thead []
        [
          tr [class "head", attribute "role" "row"]
          [
            th [class ("name " ++ isColoredTitleColName), onClick (ChangeSorting Name)][text "Name", sortingArrowName]
          , th [class ("description " ++ isColoredTitleColDesc), onClick (ChangeSorting Description)][text "Description", sortingArrowDesc]
          , th [class "change"][text "Change"]
          ]
        ]
      , constructTableLine secretsToDisplay model.openedDescription

      ]
  ]

displayActionZone : Html Msg
displayActionZone =
  div [id "actions_zone"]
  [
    div [class "createSecret"]
    [
      button [class "btn btn-success new-icon space-bottom space-top", onClick (OpenModal Add Nothing)][ text "Create Secret" ]
    ]
  ]

displayModal : Model -> Html Msg
displayModal model =
    let
      descShouldBeDisable = case model.openModalMode of
        Delete -> True
        _ -> False
      focusedSecretName =
        case model.focusOn of
          Just s  -> s.name
          Nothing -> ""
      focusedSecretDescription =
        case model.focusOn of
          Just s  -> s.description
          Nothing -> ""
      placeholderDescDelete = case model.openModalMode of
        Delete -> focusedSecretDescription
        _ -> ""
      title =
        case model.openModalMode of
          Read -> "Unknown action should not happend"
          Add -> "Add a Secret"
          Edit -> "Update Secret " ++ focusedSecretName
          Delete -> "Delete Secret " ++ focusedSecretName
      buttonText =
        case model.openModalMode of
          Read -> ""
          Add -> "Create"
          Edit -> "Update"
          Delete -> "Delete"
      inputName =
        case model.openModalMode of
          Read ->
            div[][]
          Add ->
            input [class ("form-control vresize col-lg-12 col-sm-12"), onInput InputName][]
          _ ->
            input [class "form-control vresize col-lg-12 col-sm-12", disabled True, onInput InputName, placeholder focusedSecretName][text focusedSecretName]

   in
   case model.openModalMode of
     Read -> div [][]
     _    ->
       div [class "modal-dialog"]
       [
         div [class "modal-content"]
         [
           div [class "modal-header"]
           [
             div [class "close", attribute "data-dismiss" "modal", onClick CloseModal]
             [
               i [class "fas fa-times"][]
             ]
           , h4 [class "modal-title"][text title]
           ]
         , div [class "modal-body"]
           [
             div [class "row wbBaseField form-group"]
             [
               label [class "col-lg-3 col-sm-12 col-xs-12 text-right wbBaseFieldLabel"]
               [
                 span [class "text-fit"][text "Name"]
               ]
             , div [class "col-lg-9 col-sm-12 col-xs-12"]
               [
                 inputName
               ]
             ]
           , case model.openModalMode of
               Delete -> div [][]
               _      ->
                 div [class "row wbBaseField form-group"]
                 [
                   label [class "col-lg-3 col-sm-12 col-xs-12 text-right wbBaseFieldLabel"]
                   [
                     span [class "text-fit"][text "Value"]
                   ]
                 , div [class "col-lg-9 col-sm-12 col-xs-12"]
                   [
                     textarea [tabindex 2, class "value-input form-control vresize col-lg-12 col-sm-12", onInput InputValue][]
                   , div [class "text-muted small"]
                     [
                       text "The value will not be displayed in the interface, make sure to provide to description and a name to precisely identify the secret."
                     ]
                   ]
                 ]
           , div [class "row wbBaseField form-group"]
             [
               label [class "col-lg-3 col-sm-12 col-xs-12 text-right wbBaseFieldLabel"]
               [
                 span [class "text-fit"][text "Description"]
               ]
             , div [class "col-lg-9 col-sm-12 col-xs-12"]
               [
                 textarea [tabindex 2, id "description-input", class "form-control vresize col-lg-12 col-sm-12", placeholder placeholderDescDelete, disabled descShouldBeDisable, onInput InputDescription][]
               ]
             ]
           ]
         , div [class "modal-footer"]
           [
             button [id "cancel", class "btn btn-default", onClick CloseModal][text "Cancel"]
           , case model.openModalMode of
               Add ->
                 button [id "saveSecretButton", class "btn btn-success", onClick (SubmitSecret Add)][text buttonText]
               Edit ->
                 button [id "saveSecretButton", class "btn btn-success", onClick (SubmitSecret Edit)][text buttonText]
               Delete ->
                 button [id "saveSecretButton", class "btn btn-success", onClick (SubmitSecret Delete)][text buttonText]
               Read ->
                 button [id "error-btn", class "btn btn-success", disabled True][]
           ]
         ]
      ]

displayDescriptionDetails : SecretInfo -> Html Msg
displayDescriptionDetails s =
  tr [class "details secretsDescription"]
  [
    td [class "details", colspan 3]
    [
      span [class "secretsDescriptionDetails"]
      [
        span []
        [
          ul [class "evlogviewpad"] (
            Markdown.toHtml Nothing s.description
          )
        ]
      ]
    ]
  ]

view : Model -> Html Msg
view model =
  let
    modal =
      case model.openModalMode of
        Read -> div [][]
        _ -> div  [id "secretForm", class "modal-backdrop fade in"] []
  in
  div [class "rudder-template"]
  [
    div [class "one-col"]
    [
      div [class "main-header"]
      [
        div [class "header-title"]
        [
          h1 [][span[][text "Secret"]]
        ]
      , div[class "header-description"][text "TODO DESC"]
      ]
     --, modal
     , displayModal model
     , div[class "one-col-main"]
      [
        div [class "template-main"]
        [
          div [class "main-container"]
          [
            div [class "main-details"]
            [
              div [class "header-secret "][]
            , div [class "content-block"]
              [
                displayActionZone
              , displayTable model
              ]
            ]
          ]
        ]
      ]
    ]
  ]
