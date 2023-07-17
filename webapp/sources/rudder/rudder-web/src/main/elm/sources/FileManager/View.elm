module FileManager.View exposing (..)

import FileManager.Events exposing (..)
import Html exposing (Attribute, Html, br, div, i, input, img, label, strong, text)
import Html.Attributes exposing (attribute, class, draggable, id, src, style, title, type_, value)
import Html.Events exposing (onClick, onDoubleClick, onInput)
import Http exposing (Progress(..))
import List exposing (head, indexedMap, isEmpty, length, map, member, range, reverse, tail)
import FileManager.Model exposing (..)
import Maybe exposing (andThen, withDefault)
import String exposing (fromInt, fromFloat, join, split)
import Svg exposing (svg, path, circle)
import Svg.Attributes exposing (cx, cy, d, width, height, fill, r, viewBox)
import FileManager.Util exposing (button, isJust)
import FileManager.Vec exposing (..)

view : Model -> Html Msg
view model = if model.open
  then
    div [ class "filemanager-container"] [
    div [ class "fm-simple-screen", onContextMenu <| None, onDragOver None ]
    [ div
      [ class "fm-main"
      , onMouseMove (\_ -> None)
      ]
      [ bar model.dir model.load
      , files model
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

bar : String -> Bool -> Html Msg
bar dir load = div [ class "fm-bar" ]
  [ arrowIcon <| EnvMsg <| GetLs <| back dir
  , div [ class "fm-text" ] [ text dir ]
  , if load
      then div [ class "fm-loader" ]
      [ svg [ width "25", height "25" ]
        [ circle [ cx "50%", cy "50%", r "40%" ] []
        ]
      ]
      else div [] []
  ]

files : Model -> Html Msg
files model = div
  [ class "fm-files"
  , class <| if model.drag then "fm-drag" else ""
  , onMouseDown (\x y -> EnvMsg <| MouseDown Nothing x y)
  , onMouseMove <| EnvMsg << MouseMove
  , onMouseUp <| EnvMsg << MouseUp Nothing
  , onDragEnter ShowDrop
  ]
  [ div [ class "fm-wrap" ]
    [ div [ class "fm-fluid" ]
      <| indexedMap (renderFile model) model.files
      ++ reverse (map (renderUploading model.progress) (range 0 <| model.filesAmount - 1))
    ]
  , if model.showDrop && model.hasWriteRights
    then div [ class "fm-drop", onDragLeave HideDrop, onDrop GotFiles ] []
    else div [] []
  ]

arrowIcon : Msg -> Html Msg
arrowIcon msg = button [ class "fm-arrow", onClick msg ]
  [
    svg
    [ attribute "height" "24"
    , viewBox "0 0 24 24"
    , attribute "width" "24"
    , attribute "xmlns" "http://www.w3.org/2000/svg"
    ]
    [ path [ d "M0 0h24v24H0z", fill "none" ] []
    , path [ d "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z", fill "#ffffff" ] []
    ]
  ]

back : String -> String
back route = "/" ++ (join "/" <| withDefault [] <| andThen tail <| tail <| reverse <| split "/" route)

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
      ++ (if dir == clipboardDir && member file clipboardFiles then " fm-cut" else "")
  , title file.name
  , onMouseDown <| (\x y -> EnvMsg <| MouseDown (Just file) x y)
  , onMouseUp <| EnvMsg << (MouseUp <| Just file)
  , onDoubleClick <| if file.type_ == "dir" then EnvMsg <| GetLs <| dir ++ file.name ++ "/" else Download
  ]
  [ renderThumb thumbnailsUrl api dir file
  , div [ class "fm-name" ] [ text file.name ]
  ]

renderThumb : String -> String -> String -> FileMeta -> Html Msg
renderThumb thumbApi api dir file = if file.type_ == "dir"
  then div [ class "fm-thumb" ] [ fileIcon ]
  else renderFileThumb api thumbApi <| dir ++ file.name

fileIcon : Html Msg
fileIcon = svg [ attribute "height" "48", viewBox "0 0 24 24", attribute "width" "48", attribute "xmlns" "http://www.w3.org/2000/svg" ]
  [ path [ d "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z", fill "#ffb900" ]
      []
  , path [ d "M0 0h24v24H0z", fill "none" ]
      []
  ]

renderFileThumb : String -> String -> String -> Html Msg
renderFileThumb api thumbApi fullName = if member (getExt fullName) ["jpg", "jpeg", "png", "PNG"]
  then div [ class "fm-thumb" ]
    [ img [ src <| thumbApi ++ fullName, draggable "false" ] []
    ]
  else div [ class "fm-thumb" ] [ folderIcon ]

folderIcon : Html Msg
folderIcon = svg [ attribute "height" "48", viewBox "0 0 24 24", attribute "width" "48", attribute "xmlns" "http://www.w3.org/2000/svg" ]
  [ path [ d "M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z", fill "#0078d4" ]
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
      [ button [ class "div white", onClick Download ] [ text "Download" ]
      , button (if many then [ class "div white disabled" ] else [ class "div white", onClick (OpenNameDialog (Rename file file.name)) ]) [ text "Rename" ]
      , button [ class "div white", onClick Cut ] [ text "Cut" ]
      , button (if paste && file.type_ == "dir" then [ class "div white", onClick Paste ] else [ class "div white disabled" ]) [ text "Paste" ]
      , button [ class "div white", onClick Delete ] [ text "Delete" ]
      ]
    Nothing ->
      [ button [ class "div white", onClick ChooseFiles ] [ text "Upload" ]
      , button [ class "div white", onClick (OpenNameDialog (NewDir "")) ] [ text "New folder" ]
      , button [ class "div white", onClick (OpenNameDialog (NewFile "")) ] [ text "New file" ]
      , button (if paste then [ class "div white", onClick Paste ] else [ class "div white disabled" ]) [ text "Paste" ]
      ]

nameDialog : DialogAction -> Html Msg
nameDialog dialogState =
  let
    n = case dialogState of
               Closed -> ""
               Rename _ name -> name
               NewFile name -> name
               NewDir name -> name
  in
  case dialogState of
    Closed -> text ""
    _ ->
      div [ class "fm-screen" ]
      [ div [ class "fm-modal" ]
      [ label []
          [ strong [] [ text "Name" ]
          , br [] []
          , input [ type_ "text", value n, onInput Name ] []
          ]
      , div []
          [ button [ class "fm-button", onClick CloseNameDialog ] [ text "Cancel" ]
          , button [ class "fm-button", onClick ConfirmNameDialog ] [ text "Ok" ]
          ]
        ]
      ]
