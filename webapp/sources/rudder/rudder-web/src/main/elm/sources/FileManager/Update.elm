module FileManager.Update exposing (..)

import FileManager.Action exposing (..)
import Browser.Navigation exposing (reload)
import FileManager.Env exposing (handleEnvMsg)
import File.Download
import File.Select
import List exposing (map, filter)
import Http
import FileManager.Model exposing (..)
import Maybe
import FileManager.Vec exposing (..)

init : Flags -> (Model, Cmd Msg)
init flags = (initModel flags, let { api, dir } = flags in listDirectory api dir)

initModel : Flags -> Model
initModel { api, thumbnailsUrl, downloadsUrl, dir, hasWriteRights } =
  { api = api
  , thumbnailsUrl = thumbnailsUrl
  , downloadsUrl = downloadsUrl
  , dir = dir
  , open = False
  , load = False
  , pos1 = Vec2 0 0
  , pos2 = Vec2 0 0
  , mouseDown = False
  , ctrl = False
  , caller = Nothing
  , files = []
  , showBound = False
  , bound = newBound
  , bounds = []
  , selected = []
  , drag = False
  , showContextMenu = False
  , selectedBin = []
  , showDrop = False
  , filesAmount = 0
  , progress = Http.Receiving { received = 0, size = Just 0 }
  , dialogState = Closed
  , clipboardDir = ""
  , clipboardFiles = []
  , uploadQueue = []
  , hasWriteRights = hasWriteRights
  }

update : Msg -> Model -> (Model, Cmd Msg)
update msg model = case msg of
  EnvMsg message -> handleEnvMsg message model
  ChooseFiles -> (model, File.Select.files [] GotFiles)
  ShowDrop -> ({ model | showDrop = True }, Cmd.none)
  HideDrop -> ({ model | showDrop = False }, Cmd.none)
  GotFiles file files ->
    ( { model
      | showContextMenu = False
      , uploadQueue = files
      , filesAmount = List.length files + 1
      , showDrop = False
      }
      , FileManager.Action.upload model.api model.dir file
    )
  Progress progress -> ({ model | progress = progress }, Cmd.none)
  Cancel -> (model, reload)
  Uploaded result -> case result of
    Ok () -> case model.uploadQueue of
        file :: files ->
          ( { model
            | filesAmount = model.filesAmount - 1
            , uploadQueue = files
            },
            Cmd.batch
              [ listDirectory model.api model.dir
              , FileManager.Action.upload model.api model.dir file
              ]
          )
        _ -> ({ model | filesAmount = 0 }, listDirectory model.api model.dir)
    Err _ -> (model, Cmd.none)
  OpenNameDialog state->
    ( { model
      | dialogState = state
      , showContextMenu = False
      }
      , Cmd.none
    )
  CloseNameDialog -> ({ model | dialogState = Closed }, Cmd.none)
  Name name ->
   let
     dialogState = case model.dialogState of
                     Closed -> Closed
                     NewDir _ -> NewDir name
                     NewFile _ -> NewFile name
                     Rename f _ -> Rename f name
   in
     ({ model | dialogState = dialogState }, Cmd.none)
  ConfirmNameDialog ->
    case model.dialogState of
      Closed -> (model,Cmd.none)
      NewDir s -> ({ model | dialogState = Closed, load = True }, newDir model.api model.dir s)
      NewFile s -> ({ model | dialogState = Closed, load = True }, newFile model.api model.dir s)
      Rename f s -> ({ model | dialogState = Closed, load = True },FileManager.Action.rename model.api model.dir f.name s)
  Download ->
    ( { model
      | showContextMenu = False
      }
      , Cmd.batch
      <| map (File.Download.url << (++) (model.downloadsUrl  ++ "?action=download&path=" ++ model.dir) << .name)
      <| filter (.type_ >> (/=) "dir") model.selected
    )
  Cut -> ({ model | clipboardDir = model.dir, clipboardFiles = model.selected, showContextMenu = False }, Cmd.none)
  Paste -> if model.dir == model.clipboardDir
    then ({ model | clipboardFiles = [], showContextMenu = False }, Cmd.none)
    else
      ( { model
      | clipboardFiles = []
      , showContextMenu = False
      , load = True
      }
      , case model.caller of
          Just file -> if file.type_ == "dir"
            then move model.api model.clipboardDir model.clipboardFiles <| model.dir ++ file.name ++ "/"
            else Cmd.none
          Nothing -> move model.api model.clipboardDir model.clipboardFiles model.dir
      )
  Delete -> ({ model | showContextMenu = False, load = True }, delete model.api  model.dir model.selected)
  UpdateApiPath apiPath -> ({model | api = apiPath, downloadsUrl =  apiPath } , listDirectory apiPath "")

  None -> (model, Cmd.none)
