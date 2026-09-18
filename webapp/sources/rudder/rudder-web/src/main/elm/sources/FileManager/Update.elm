module FileManager.Update exposing (..)

import Browser.Navigation exposing (reload)
import Dict exposing (Dict)
import File exposing (File)
import File.Download
import File.Select
import FileManager.Action exposing (..)
import FileManager.Env exposing (handleEnvMsg)
import FileManager.Model exposing (..)
import FileManager.Port exposing (errorNotification)
import FileManager.Util exposing (getDirPath, maxUploadSizeText, processApiDetailedError, processApiError)
import FileManager.Vec exposing (..)
import Http exposing (Error(..))
import Http.Detailed
import List exposing (filter, map)
import List.Nonempty as NonEmptyList
import Maybe
import String.Extra
import Ui.Datatable exposing (defaultTableFilters)


init : Flags -> ( Model, Cmd Msg )
init flags =
    ( initModel flags
    , let
        { api, dir, initRun } =
            flags
      in
      if initRun then
        listDirectory api [ dir ]

      else
        Cmd.none
    )


initModel : Flags -> Model
initModel { api, thumbnailsUrl, downloadsUrl, dir, hasWriteRights } =
    { api = api
    , thumbnailsUrl = thumbnailsUrl
    , downloadsUrl = downloadsUrl
    , dir = [ dir ]
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
    , uploadStatus = NoUpload
    , dialogState = Closed
    , clipboardDir = ""
    , clipboardFiles = []
    , hasWriteRights = hasWriteRights
    , viewMode = GridView
    , tableFilters = defaultTableFilters FileName
    , tree = Dict.empty
    , maxUploadSize = defaultMaxUploadSize
    }


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        EnvMsg message ->
            handleEnvMsg message model

        ChooseFiles ->
            ( model, File.Select.files [] GotFiles )

        ShowDrop ->
            ( { model | showDrop = True }, Cmd.none )

        HideDrop ->
            ( { model | showDrop = False }, Cmd.none )

        GotFiles file files ->
            let
                ( tooBig, allowed ) =
                    List.partition (\f -> File.size f > model.maxUploadSize) (file :: files)

                closedModel =
                    { model | showContextMenu = False, showDrop = False }

                notifyTooBig =
                    case tooBig of
                        [] ->
                            Cmd.none

                        _ ->
                            errorNotification
                                ("Maximum size of uploaded file is "
                                    ++ maxUploadSizeText model.maxUploadSize
                                    ++ " for each individual file, so the following "
                                    ++ String.Extra.pluralize "file" "files" (List.length tooBig)
                                    ++ " were not uploaded: "
                                    ++ String.join ", " (List.map File.name tooBig)
                                )
            in
            case NonEmptyList.fromList allowed of
                Nothing ->
                    ( closedModel, notifyTooBig )

                Just allowedFiles ->
                    let
                        uploadStatus =
                            newUpload allowedFiles
                    in
                    ( { closedModel | uploadStatus = uploadStatus }
                    , Cmd.batch [ notifyTooBig, processCurrentUpload model uploadStatus ]
                    )

        Progress progress ->
            ( { model | uploadStatus = updateUploadStatus (setProgress progress) model.uploadStatus }, Cmd.none )

        Cancel ->
            ( model, reload )

        Uploaded result ->
            case result of
                Ok ( _, _ ) ->
                    let
                        uploadStatus =
                            nextUpload model.uploadStatus
                    in
                    ( { model | uploadStatus = uploadStatus }
                    , Cmd.batch
                        [ listDirectory model.api model.dir
                        , processCurrentUpload model uploadStatus
                        ]
                    )

                Err err ->
                    ( { model | uploadStatus = NoUpload }
                    , processApiDetailedError "uploading file" decoderUploadResponse err
                    )

        OpenNameDialog state ->
            let
                cmd =
                    case state of
                        Edit file _ ->
                            getContent model.api (getDirPath model.dir) file

                        _ ->
                            Cmd.none
            in
            ( { model
                | dialogState = state
                , showContextMenu = False
              }
            , cmd
            )

        CloseNameDialog ->
            ( { model | dialogState = Closed }, Cmd.none )

        FileManager.Model.Name name ->
            let
                dialogState =
                    case model.dialogState of
                        Closed ->
                            Closed

                        NewDir _ ->
                            NewDir name

                        NewFile _ ->
                            NewFile name

                        Rename f _ ->
                            Rename f name

                        Edit f _ ->
                            Edit f name
            in
            ( { model | dialogState = dialogState }, Cmd.none )

        ConfirmNameDialog ->
            case model.dialogState of
                Closed ->
                    ( model, Cmd.none )

                NewDir s ->
                    ( { model | dialogState = Closed, load = True }, newDir model.api (getDirPath model.dir) s )

                NewFile s ->
                    ( { model | dialogState = Closed, load = True }, newFile model.api (getDirPath model.dir) s )

                Edit file content ->
                    ( { model | dialogState = Closed, load = True }, saveContent model.api (getDirPath model.dir) file content )

                Rename _ "" ->
                    update (FileUpdate (FileValidationError "File name should not be empty")) model

                Rename f name ->
                    if String.contains "/" name || String.contains "\\0" name then
                        update (FileUpdate (FileValidationError "File name should not contain invalid characters such as '/' or '\\0'")) model

                    else
                        ( { model
                            | dialogState = Closed
                            , load = True
                          }
                        , FileManager.Action.rename model.api (getDirPath model.dir) f.name name
                        )

        FileUpdate (FileValidationError err) ->
            ( model, errorNotification err )

        FileUpdate (FileUpdateHttpError err) ->
            ( model, processApiError "updating file" err )

        Download ->
            ( { model
                | showContextMenu = False
              }
            , Cmd.batch <|
                map (download model.downloadsUrl model.dir) <|
                    filter (.type_ >> (/=) "dir") model.selected
            )

        Downloaded fileMeta (Ok bytes) ->
            ( model, File.Download.bytes fileMeta.name fileMeta.type_ bytes )

        Downloaded fileMeta (Err err) ->
            ( model, processApiError ("downloading file " ++ fileMeta.name) err )

        Cut ->
            ( { model | clipboardDir = getDirPath model.dir, clipboardFiles = model.selected, showContextMenu = False }, Cmd.none )

        Paste ->
            if getDirPath model.dir == model.clipboardDir then
                ( { model | clipboardFiles = [], showContextMenu = False }, Cmd.none )

            else
                ( { model
                    | clipboardFiles = []
                    , showContextMenu = False
                    , load = True
                  }
                , case model.caller of
                    Just file ->
                        if file.type_ == "dir" then
                            move model.api model.clipboardDir model.clipboardFiles <| getDirPath model.dir ++ file.name ++ "/"

                        else
                            Cmd.none

                    Nothing ->
                        move model.api model.clipboardDir model.clipboardFiles (getDirPath model.dir)
                )

        Delete ->
            ( { model | showContextMenu = False, load = True }, delete model.api (getDirPath model.dir) model.selected )

        UpdateApiPath apiPath ->
            ( { model | api = apiPath, downloadsUrl = apiPath }, listDirectory apiPath [ "/" ] )

        ChangeViewMode viewMode ->
            ( { model | viewMode = viewMode }, Cmd.none )

        UpdateTableFilters tableFilters ->
            ( { model | tableFilters = tableFilters }, Cmd.none )

        None ->
            ( model, Cmd.none )


processCurrentUpload : Model -> UploadStatus File -> Cmd Msg
processCurrentUpload model uploadStatus =
    case currentUpload uploadStatus of
        Nothing ->
            Cmd.none

        Just file ->
            FileManager.Action.upload model.api (getDirPath model.dir) file
