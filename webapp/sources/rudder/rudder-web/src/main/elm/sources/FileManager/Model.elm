module FileManager.Model exposing (..)

import Browser.Dom exposing (Element)
import Bytes exposing (Bytes)
import Dict exposing (Dict)
import File exposing (File)
import FileManager.Vec exposing (..)
import Http exposing (Error)
import Http.Detailed
import List.Nonempty as NonEmptyList
import Ui.Datatable exposing (TableFilters)


type alias Flags =
    { api : String
    , thumbnailsUrl : String
    , downloadsUrl : String
    , dir : String
    , hasWriteRights : Bool
    , initRun : Bool
    , maxUploadSize : Int
    }


type ViewMode
    = ListView
    | GridView


type SortBy
    = FileName
    | FileSize
    | FileDate
    | FileRights


{-| Parametrized to avoid using "File.File", which is opaque, we can replace it for testability
-}
type UploadStatus file
    = NoUpload
    | PendingUpload (UploadState file)


type alias UploadState file =
    { progress : Http.Progress
    , uploadQueue : NonEmptyList.Nonempty file
    }


{-| Apply a change to the upload in progress (the upload state), with transformation function
-}
updateUploadStatus : (UploadState file -> UploadState file) -> UploadStatus file -> UploadStatus file
updateUploadStatus f status =
    case status of
        NoUpload ->
            NoUpload

        PendingUpload state ->
            PendingUpload (f state)


setProgress : Http.Progress -> UploadState file -> UploadState file
setProgress progress state =
    { state | progress = progress }


newUpload : NonEmptyList.Nonempty file -> UploadStatus file
newUpload queue =
    PendingUpload { progress = Http.Sending { sent = 0, size = 0 }, uploadQueue = queue }


nextUpload : UploadStatus file -> UploadStatus file
nextUpload status =
    case status of
        NoUpload ->
            NoUpload

        PendingUpload state ->
            case state.uploadQueue |> NonEmptyList.tail |> NonEmptyList.fromList of
                Nothing ->
                    NoUpload

                Just remaining ->
                    newUpload remaining


{-| Only a single upload in progress at the same time, others have no progress.
The current progress is at last position, since only the last pending upload
is displayed with progress in the view
-}
currentUploadsInProgress : UploadStatus file -> List (Maybe Http.Progress)
currentUploadsInProgress status =
    case status of
        NoUpload ->
            []

        PendingUpload { progress, uploadQueue } ->
            List.repeat (NonEmptyList.length uploadQueue - 1) Nothing
                ++ [ Just progress ]


currentUpload : UploadStatus file -> Maybe file
currentUpload status =
    case status of
        NoUpload ->
            Nothing

        PendingUpload state ->
            Just (NonEmptyList.head state.uploadQueue)


isUploading : UploadStatus file -> Bool
isUploading status =
    case status of
        NoUpload ->
            False

        PendingUpload _ ->
            True


type alias Model =
    { api : String
    , thumbnailsUrl : String
    , downloadsUrl : String
    , dir : List String
    , open : Bool
    , load : Bool
    , pos1 : Vec2
    , pos2 : Vec2
    , mouseDown : Bool
    , ctrl : Bool
    , caller : Maybe FileMeta
    , files : List FileMeta
    , showBound : Bool
    , bound : Bound
    , bounds : List Bound
    , selected : List FileMeta
    , drag : Bool
    , showContextMenu : Bool
    , selectedBin : List FileMeta
    , showDrop : Bool
    , uploadStatus : UploadStatus File
    , dialogState : DialogAction
    , clipboardDir : String
    , clipboardFiles : List FileMeta
    , hasWriteRights : Bool
    , viewMode : ViewMode
    , tableFilters : TableFilters SortBy
    , tree : Dict String TreeItem
    , maxUploadSize : Int
    }


type alias TreeItem =
    { name : String
    , parents : List String
    , childs : List String
    }


type alias FileMeta =
    { name : String
    , type_ : String
    , size : Int
    , date : String
    , rights : String
    }


type Msg
    = EnvMsg EnvMsg
    | ChooseFiles
    | ShowDrop
    | HideDrop
    | GotFiles File (List File)
    | Progress Http.Progress
    | Cancel
    | Uploaded (Result (Http.Detailed.Error String) ( Http.Metadata, UploadResponse ))
    | OpenNameDialog DialogAction
    | CloseNameDialog
    | ConfirmNameDialog
    | FileUpdate FileUpdateError
    | Name String
    | Download
    | Downloaded FileMeta (Result Http.Error Bytes)
    | Cut
    | Paste
    | Delete
    | UpdateApiPath String
    | None
    | ChangeViewMode ViewMode
    | UpdateTableFilters (TableFilters SortBy)


type EnvMsg
    = Open ()
    | Close
    | Accept
    | MouseDown (Maybe FileMeta) Vec2 Bool
    | GetBounds (Result Browser.Dom.Error (List Element))
    | MouseMove Vec2
    | MouseUp (Maybe FileMeta) Int
    | GetLs String
    | GetLsTree (List String)
    | LsGotten String (Result Error (List FileMeta))
    | Refresh (Result Error ())
    | GotContent (Result Error String)


type DialogAction
    = Rename FileMeta String
    | NewFile String
    | NewDir String
    | Edit String String
    | Closed


type FileUpdateError
    = FileValidationError String
    | FileUpdateHttpError Http.Error


type alias UploadResponse =
    { success : Bool
    , error : Maybe String
    }
