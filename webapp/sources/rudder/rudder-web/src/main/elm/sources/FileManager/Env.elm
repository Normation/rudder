module FileManager.Env exposing (..)

import Browser.Dom exposing (getElement)
import List exposing (filter, indexedMap, map, member, reverse)
import List.Extra exposing (takeWhile)
import Platform.Cmd
import String exposing (fromInt)
import Task exposing (sequence)
import Tuple exposing (first, second)
import Dict exposing (Dict)

import FileManager.Action exposing (..)
import FileManager.Port exposing (close)
import FileManager.Model exposing (..)
import FileManager.Vec exposing (..)
import FileManager.Util exposing (..)

handleEnvMsg : EnvMsg -> Model -> (Model, Cmd Msg)
handleEnvMsg msg model = case msg of
  Open () -> ({ model | open = True, dir = ["/"] }, Cmd.none)
  Close -> ({ model | open = False, selected = [] }, close [])
  Accept -> ({ model | open = False, selected = [] }, close <| reverse <| map ((++) (getDirPath model.dir) << .name) model.selected)
  MouseDown maybe pos1 ctrl ->
    ( { model
      | mouseDown = True
      , ctrl = ctrl
      , caller = maybe
      , pos1 = pos1
      , selected = case maybe of
          Just file -> if member file model.selected
            then if ctrl then filter ((/=) file) model.selected else model.selected
            else if ctrl then file :: model.selected else [file]
          Nothing -> []
      , showContextMenu = False
      }
      , getBounds model.files
    )
  GetBounds result -> case result of
    Ok(elements) -> ({ model | bounds = map .element elements }, Cmd.none)
    Err _ -> (model, Cmd.none)
  MouseMove pos2 ->
    ( { model
      | pos2 = pos2
      , showBound = model.mouseDown && (not << isJust) model.caller
      , bound = toBound model.pos1 pos2
      , drag = model.hasWriteRights && model.mouseDown && isJust model.caller && isFar model.pos1 pos2 && model.filesAmount <= 0
      }
      , Cmd.none
    )
  MouseUp maybe buttons -> if buttons == 2
    then
      ( { model
        | mouseDown = False
        , drag = False
        , showContextMenu = case maybe of
            Just file -> not <| (getDirPath model.dir) == model.clipboardDir && member file model.clipboardFiles
            Nothing -> True
        , selected = case maybe of
            Just _ -> model.selected
            Nothing -> []
        }
        , Cmd.none
      )
    else
      ( { model
        | mouseDown = False
        , drag = False
        , selected = if model.showBound
            then map second <| filter (touchesBound model.bound << first) <| zip model.bounds model.files
            else case maybe of
              Just file -> if model.drag || model.ctrl then model.selected else [file]
              Nothing -> []
        , selectedBin = model.selected
        , load = case maybe of
            Just file -> if model.drag && (file.type_ == "dir") && (not << member file) model.selected
              then True
              else False
            Nothing -> False
        }
        , case maybe of
            Just file -> if model.drag && (file.type_ == "dir") && (not << member file) model.selected
              then move model.api (getDirPath model.dir) model.selected <| "/" ++ file.name ++ "/"
              else Cmd.none
            Nothing -> Cmd.none
      )

  GetLs dir ->
    let
      newDir = String.split "/" dir
    in
      ({ model | dir = newDir, files = [], load = True }, listDirectory model.api newDir)

  GetLsTree dir ->
    ({ model | dir = dir, files = [], load = True }, listDirectory model.api dir)

  LsGotten folder result -> case result of
    Ok files ->
      let
        toItems : List FileMeta -> List String
        toItems fs = fs
          |> List.filter (\f -> f.type_ == "dir")
          |> List.map .name
          |> List.sort

        childs = toItems files

        newTree = case Dict.get folder model.tree of
          Just ti -> Dict.insert folder (TreeItem folder ti.parents childs) model.tree
          Nothing ->
            let
              values = Dict.values model.tree

              parents = case List.Extra.find (\ti -> List.member folder ti.childs) values of
                Just p  -> List.append [p.name] p.parents
                Nothing -> []

            in
              Dict.insert folder (TreeItem folder parents childs) model.tree

        filters = model.filters
        newFilters = {filters | opened = folder :: filters.opened}
      in
        ({ model | files = files, selected = [], load = False, tree = newTree, filters = newFilters }, Cmd.none)
    Err _ -> (model, Cmd.none)

  Refresh result -> case result of
    Ok () -> (model, listDirectory model.api model.dir)
    Err _ -> (model, Cmd.none)
  GotContent result ->
      case model.dialogState of
        Edit f _ ->
          case result of
            Ok content -> ({model | dialogState = Edit f content}, Cmd.none)
            Err _ -> (model, Cmd.none)
        _ -> (model, Cmd.none)


getBounds : List FileMeta -> Cmd Msg
getBounds files = Task.attempt (EnvMsg << GetBounds)
  <| sequence
  <| indexedMap (\i _ -> getElement <| "fm-file-" ++ fromInt i) files
