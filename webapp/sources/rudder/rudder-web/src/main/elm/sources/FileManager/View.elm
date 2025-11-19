module FileManager.View exposing (..)

import Html exposing (Attribute, Html, br, div, i, input, textarea, img, label, strong, text, ul, table, thead, tbody, tr, th, td, ul, li, a)
import Html.Attributes exposing (attribute, class, draggable, id, src, style, title, type_, value, placeholder, disabled)
import Html.Events exposing (onClick, onDoubleClick, onInput)
import Http exposing (Progress(..))
import List exposing (head, indexedMap, isEmpty, length, map, member, range, reverse, tail)
import List.Extra exposing (last)
import Maybe exposing (andThen, withDefault)
import String exposing (fromInt, fromFloat, join, split)
import Svg exposing (svg, path, circle)
import Svg.Attributes exposing (cx, cy, d, width, height, fill, r, viewBox)
import NaturalOrdering as N exposing (compare)
import Dict exposing (Dict)

import FileManager.Model exposing (..)
import FileManager.Util exposing (button, isJust, getDirPath)
import FileManager.Vec exposing (..)
import FileManager.Events exposing (..)

import Ui.Datatable exposing (filterSearch, sortTable, thClass, SortOrder(..))


view : Model -> Html Msg
view model = if model.open
  then
    div [ class "filemanager-container"] [
    div [ class "fm-simple-screen", onContextMenu <| None, onDragOver None ]
    [ div
      [ class "fm-main"
      , onMouseMove (\_ -> None)
      ]
      [ bar model
      , div[class "files-container"]
        [ filesTree model
        , ( if model.viewMode == GridView then
          filesGrid model
          else
          filesList model
          )
        ]
      , div [ class "fm-control" ]
        [ button [ onClick <| EnvMsg Accept ] [ text "Close" ]
        ]
      , if model.showBound then renderHelper model.bound else div [] []
      , if model.drag then renderCount model.pos2 model.selected else div [] []
      , if model.showContextMenu && model.hasWriteRights
          then contextMenu model.pos1 model.caller (not <| isEmpty model.clipboardFiles) (length model.selected > 1) model.filesAmount
          else div [] []
      , nameDialog model.dialogState
      ]
    ]
    ]
  else div [] []

bar : Model -> Html Msg
bar model =
  let
    dir  = model.dir
    load = model.load
    tableFilters = model.tableFilters
  in
  div [ class "fm-bar" ]
  [ arrowIcon dir <| EnvMsg <| GetLs (back dir)
  , div [ class "fm-text" ] [ text (getDirPath dir) ]
  , if load
      then div [ class "fm-loader" ]
      [ svg [ width "25", height "25" ]
        [ circle [ cx "50%", cy "50%", r "40%" ] []
        ]
      ]
      else div [] []
  , div [class "fm-bar-actions"]
    [ div [class "btn-group"]
      [ button [attribute "data-bs-toggle" "dropdown", id "dropDownMenuSearch", type_ "button", class "btn btn-flat btn-sm dropdown-toggle"]
        [ i [class "fa fa-search mr2"][]
        ]
      , ul [class "dropdown-menu search-dropdown pull-right"]
        [ li[]
          [ input [class "form-control", type_ "text", placeholder "Search...", onInput (\s -> UpdateTableFilters {tableFilters | filter = s}), value tableFilters.filter][]]
        ]
      ]
    , ( if model.viewMode == GridView then
      button [class "btn btn-flat btn-sm", onClick (ChangeViewMode ListView)]
      [ i [class "fa fa-list"][]
      ]
      else
      button [class "btn btn-flat btn-sm", onClick (ChangeViewMode GridView)]
      [ i [class "fa fa-th-large" ][]
      ]
      )
    , div [class "btn-group"]
      [ button [attribute "data-bs-toggle" "dropdown", id "more", type_ "button", class "btn btn-flat btn-sm dropdown-toggle"]
        [ i [class "fa fa-ellipsis-v"][]
        ]
      , mainContextMenu
      ]
    , button [ title "Close", class"btn btn-flat btn-sm", onClick <| EnvMsg Accept ]
      [ i [class "fa fa-times"][]
      ]
    ]
  ]

filesTree : Model -> Html Msg
filesTree model =
  let
    openedDir = model.tableFilters.openedRows

    listFolders : String -> Html Msg
    listFolders name =
      case Dict.get name model.tree of
        Nothing -> text ""
        Just fs ->
          ul[]
          ( fs.childs
            |> List.map (\f ->
              let
                path =  List.append fs.parents [fs.name, f] |> getDirPath
              in
                li [ ]
                [ a [ onClick <| EnvMsg <| GetLsTree (List.append fs.parents [fs.name, f] ) ]
                  [ folderIcon 20
                  , text f
                  ]
                , if Dict.member path openedDir then listFolders path else text ""
                ]
            )
          )
  in
    div [class "fm-filetree"]
    [ listFolders "/" ]

currentDir : List String -> String
currentDir dirList = case List.Extra.last dirList of
  Just f  -> f
  Nothing -> "/"

filesGrid : Model -> Html Msg
filesGrid model =
  let
    currentFiles =
      model.files
      |> List.filter (\f -> (filterSearch model.tableFilters.filter (searchField f)))
      |> List.sortBy .name
      |> List.sortBy .type_
      |> indexedMap (renderFile model)
  in
    div
    [ class "fm-files"
    , class <| if model.drag then "fm-drag" else ""
    , onMouseDown (\x y -> EnvMsg <| MouseDown Nothing x y)
    , onMouseMove <| EnvMsg << MouseMove
    , onMouseUp <| EnvMsg << MouseUp Nothing
    , onDragEnter ShowDrop
    ]
    [ div [ class "fm-wrap" ]
      [ div [ class "fm-fluid" ]
        <| currentFiles
        ++ reverse (map (renderUploading model.progress) (range 0 <| model.filesAmount - 1))
      ]
    , if model.showDrop && model.hasWriteRights
      then div [ class "fm-drop", onDragLeave HideDrop, onDrop GotFiles ] []
      else div [] []
    ]

filesList : Model -> Html Msg
filesList model =
  div
  [ class "fm-files-list"
  , class <| if model.drag then "fm-drag" else ""
  , onMouseDown (\x y -> EnvMsg <| MouseDown Nothing x y)
  , onMouseMove <| EnvMsg << MouseMove
  , onMouseUp <| EnvMsg << MouseUp Nothing
  , onDragEnter ShowDrop
  ]
  [ table [ class "dataTable" ]
    [ thead[]
      [ tr[class "head"]
        [ th[class (thClass model.tableFilters FileName   ), onClick (UpdateTableFilters (sortTable model.tableFilters FileName   ))] [ text "Name" ]
        , th[class (thClass model.tableFilters FileSize   ), onClick (UpdateTableFilters (sortTable model.tableFilters FileSize   ))] [ text "Size" ]
        , th[class (thClass model.tableFilters FileDate   ), onClick (UpdateTableFilters (sortTable model.tableFilters FileDate   ))] [ text "Date" ]
        , th[class (thClass model.tableFilters FileRights ), onClick (UpdateTableFilters (sortTable model.tableFilters FileRights ))] [ text "Permissions" ]
        ]
      ]
    , tbody []
      <| ( model.files
        |> List.filter (\f -> (filterSearch model.tableFilters.filter (searchField f)))
        |> List.sortWith (getSortFunction model)
        |> indexedMap (renderFileList model)
        )
      ++ reverse (map (renderUploading model.progress) (range 0 <| model.filesAmount - 1))
    ]
  , if model.showDrop && model.hasWriteRights
    then div [ class "fm-drop", onDragLeave HideDrop, onDrop GotFiles ] []
    else div [] []
  ]

arrowIcon : List String -> Msg -> Html Msg
arrowIcon dir msg =
  let
    disable = List.length dir <= 1
  in
    button [ class "fm-arrow", onClick msg , disabled disable]
    [ svg
      [ attribute "height" "24"
      , viewBox "0 0 24 24"
      , attribute "width" "24"
      , attribute "xmlns" "http://www.w3.org/2000/svg"
      ]
      [ path [ d "M0 0h24v24H0z", fill "none" ] []
      , path [ d "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z", fill "#ffffff" ] []
      ]
    ]

back : List String -> String
back list =
  case last list of
    Just l  -> l
    Nothing -> "/"


renderUploading : Http.Progress -> Int -> Html Msg
renderUploading progress i = div [ class "fm-file fm-upload" ]
  [ div [ class "fm-thumb" ]
    [ if i == 0 then div [ class "fm-progress", style "width" (toPx <| toFloat <| getSent progress) ] [] else div [] []
    ]
  , div [ class "fm-name" ] []
  ]

getSent : Http.Progress -> Int
getSent progress = case progress of
  Sending { size, sent } -> sent * 100 // size
  _ -> 0

renderFile : Model -> Int -> FileMeta -> Html Msg
renderFile { api, thumbnailsUrl, dir, selected, clipboardDir, clipboardFiles } i file = div
  [ id <| "fm-file-" ++ fromInt i, class <| "fm-file"
      ++ (if member file selected then " fm-selected" else "")
      ++ (if (getDirPath dir) == clipboardDir && member file clipboardFiles then " fm-cut" else "")
  , title file.name
  , onMouseDown <| (\x y -> EnvMsg <| MouseDown (Just file) x y)
  , onMouseUp <| EnvMsg << (MouseUp <| Just file)
  , onDoubleClick <| if file.type_ == "dir" then EnvMsg <| GetLs (getDirPath (List.append dir [ file.name] )) else Download
  ]
  [ renderThumb thumbnailsUrl api (getDirPath dir) file
  , div [ class "fm-name" ] [ text file.name ]
  ]

renderFileList : Model -> Int -> FileMeta -> Html Msg
renderFileList { api, thumbnailsUrl, dir, selected, clipboardDir, clipboardFiles } i file =
  tr
  [ id <| "fm-file-" ++ fromInt i, class <| "fm-file-list"
      ++ (if member file selected then " fm-selected" else "")
      ++ (if (getDirPath dir) == clipboardDir && member file clipboardFiles then " fm-cut" else "")
  , title file.name
  , onMouseDown <| (\x y -> EnvMsg <| MouseDown (Just file) x y)
  , onMouseUp <| EnvMsg << (MouseUp <| Just file)
  , onDoubleClick <| if file.type_ == "dir" then EnvMsg <| (GetLs (getDirPath (List.append dir [ file.name] ))) else Download
  ]
  [ td[]
    [ div[]
      [ renderThumb thumbnailsUrl api (getDirPath dir) file
      , div [ class "fm-name" ] [ text file.name ]
      ]
    ]
  , td[][text ((String.fromInt file.size) ++ " B")]
  , td[][text file.date   ]
  , td[][text file.rights ]
  ]

renderThumb : String -> String -> String -> FileMeta -> Html Msg
renderThumb thumbApi api dir file = if file.type_ == "dir"
  then div [ class "fm-thumb" ] [ (folderIcon 48) ]
  else renderFileThumb api thumbApi <| dir ++ file.name

fileIcon : Html Msg
fileIcon =
  svg [ attribute "height" "48", viewBox "0 0 24 24", attribute "width" "48", attribute "xmlns" "http://www.w3.org/2000/svg" ]
  [ path [ d "M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z", fill "#0078d4" ]
    []
  , path [ d "M0 0h24v24H0z", fill "none" ]
    []
  ]

renderFileThumb : String -> String -> String -> Html Msg
renderFileThumb api thumbApi fullName =
  div [ class ("fm-thumb " ++ (getExt fullName)) ] [ fileIcon ]

folderIcon : Int -> Html Msg
folderIcon s =
  let
    size = String.fromInt s
  in
    svg [ attribute "height" size, viewBox "0 0 24 24", attribute "width" size, attribute "xmlns" "http://www.w3.org/2000/svg" ]
    [ path [ d "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z", fill "#ffb900" ]
      []
    , path [ d "M0 0h24v24H0z", fill "none" ]
      []
    ]

getExt : String -> String
getExt name = withDefault "" <| head <| reverse <| split "." name

renderHelper : Bound -> Html Msg
renderHelper b = div
  [ class "fm-helper"
  , style "left" (toPx b.x)
  , style "top" (toPx b.y)
  , style "width" (toPx b.width)
  , style "height" (toPx b.height)
  ] []

toPx : Float -> String
toPx n = fromFloat n ++ "px"

renderCount : Vec2 -> List FileMeta -> Html Msg
renderCount (Vec2 x y) selected = div
  [ class "fm-count"
  , style "left" (toPx <| x + 5)
  , style "top" (toPx <| y - 25)
  ]
  [ text <| fromInt <| length <| selected
  ]

contextMenu : Vec2 -> Maybe FileMeta -> Bool -> Bool -> Int -> Html Msg
contextMenu (Vec2 x y) maybe paste many filesAmount = if filesAmount > 0
  then div [ class "fm-context-menu", style "left" (toPx x), style "top" (toPx y) ]
      [ button [ class "div white cancel", onClick Cancel ] [ text "Cancel" ]
      ]
  else div [ class "fm-context-menu", style "left" (toPx x), style "top" (toPx y) ] <| case maybe of
    Just file ->
      [ (if file.type_ == "dir" then text "" else button [ class "div white", onClick (OpenNameDialog (Edit file.name "")) ] [ text "Edit" ])
      , button [ class "div white", onClick Download ] [ text "Download" ]
      , (if many then text "" else  button [ class "div white", onClick (OpenNameDialog (Rename file file.name)) ] [ text "Rename" ])
      , button [ class "div white", onClick Cut ] [ text "Cut" ]
      , (if paste && file.type_ == "dir" then button [ class "div white", onClick Paste ] [ text "Paste" ] else text "")
      , button [ class "div white text-danger", onClick Delete ] [ text "Delete" ]
      ]
    Nothing ->
      [ button [ class "div white", onClick ChooseFiles ] [ text "Upload" ]
      , button [ class "div white", onClick (OpenNameDialog (NewDir "")) ] [ text "New folder" ]
      , button [ class "div white", onClick (OpenNameDialog (NewFile "")) ] [ text "New file" ]
      , (if paste then button [ class "div white", onClick Paste ] [ text "Paste" ] else text "" )
      ]

mainContextMenu : Html Msg
mainContextMenu =
  ul[class "dropdown-menu pull-right"]
  [ li[]
    [ a [ class "dropdown-item", onClick (OpenNameDialog (NewDir "")) ]
      [ i [class "fa fa-plus"][]
      , text "New folder"
      ]
    ]
  , li[]
    [ a [ class "dropdown-item", onClick ChooseFiles ]
      [ i [class "fa fa-cloud-upload-alt"][]
      , text "Upload files"
      ]
    ]
  ]

nameDialog : DialogAction -> Html Msg
nameDialog dialogState =
  let
    n = case dialogState of
               Edit _ _ -> ""
               Closed -> ""
               Rename _ name -> name
               NewFile name -> name
               NewDir name -> name
  in
  case dialogState of

    Edit file content ->
      div [ class "fm-screen" ]
      [ div [ class "fm-modal" ]
      [ label []
          [ strong [] [ text "Content" ]
          , br [] []
          , textarea [ class "form-control font-monospace", value content, onInput FileManager.Model.Name ] []
          ]
      , div []
          [ button [ class "fm-button btn btn-default", onClick CloseNameDialog ] [ text "Cancel" ]
          , button [ class "fm-button btn", onClick ConfirmNameDialog ] [ text "Confirm" ]
          ]
        ]
      ]
    Closed -> text ""
    _ ->
      div [ class "fm-screen" ]
      [ div [ class "fm-modal" ]
      [ label []
          [ strong [] [ text "Name" ]
          , br [] []
          , input [ type_ "text", class "form-control", value n, onInput FileManager.Model.Name ] []
          ]
      , div []
          [ button [ class "fm-button btn btn-default" , onClick CloseNameDialog   ] [ text "Cancel"  ]
          , button [ class "fm-button btn btn-success" , onClick ConfirmNameDialog ] [ text "Confirm" ]
          ]
        ]
      ]


getSortFunction : Model -> FileMeta -> FileMeta -> Order
getSortFunction model f1 f2 =
  let
    order = case model.tableFilters.sortBy of
      FileName   -> N.compare f1.name f2.name
      FileSize   -> Basics.compare f1.size f2.size
      FileDate   -> N.compare f1.date f2.date
      FileRights -> N.compare f1.rights f2.rights
  in
    if model.tableFilters.sortOrder == Asc then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

searchField : FileMeta -> List String
searchField file =
  [ file.name
  ]