module FileManager.Model exposing (..)

import Browser.Dom exposing (Element)
import File exposing (File)
import Http exposing (Error)
import Http.Detailed
import FileManager.Vec exposing (..)
import Dict exposing (Dict)

import Ui.Datatable exposing (TableFilters)
import Bytes exposing (Bytes)

type alias Flags =
  { api: String
  , thumbnailsUrl: String
  , downloadsUrl: String
  , dir: String
  , hasWriteRights : Bool
  , initRun : Bool
  }

type ViewMode = ListView | GridView

type SortBy = FileName | FileSize | FileDate | FileRights

type alias Model =
  { api: String
  , thumbnailsUrl: String
  , downloadsUrl: String
  , dir: List String
  , open: Bool
  , load: Bool
  , pos1: Vec2
  , pos2: Vec2
  , mouseDown: Bool
  , ctrl: Bool
  , caller : Maybe FileMeta
  , files: List FileMeta
  , showBound: Bool
  , bound: Bound
  , bounds: List Bound
  , selected: List FileMeta
  , drag: Bool
  , showContextMenu: Bool
  , selectedBin: List FileMeta
  , showDrop: Bool
  , filesAmount: Int
  , progress: Http.Progress
  , dialogState: DialogAction
  , clipboardDir: String
  , clipboardFiles: List FileMeta
  , uploadQueue: List File
  , hasWriteRights: Bool
  , viewMode: ViewMode
  , tableFilters: TableFilters SortBy
  , tree: Dict String TreeItem
  }

type alias TreeItem =
  { name    : String
  , parents : List String
  , childs  : List String
  }

type alias FileMeta =
  { name: String
  , type_: String
  , size: Int
  , date: String
  , rights: String
  }

type Msg
  = EnvMsg EnvMsg
  | ChooseFiles
  | ShowDrop
  | HideDrop
  | GotFiles File (List File)
  | Progress Http.Progress
  | Cancel
  | Uploaded (Result (Http.Detailed.Error String) (Http.Metadata, UploadResponse))
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

type DialogAction = Rename FileMeta String | NewFile String | NewDir String | Edit String String | Closed
type FileUpdateError = FileValidationError String | FileUpdateHttpError Http.Error
type alias UploadResponse =
  { success : Bool
  , error : Maybe String
  }