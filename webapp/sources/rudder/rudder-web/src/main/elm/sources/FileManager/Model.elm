module FileManager.Model exposing (..)

import Browser.Dom exposing (Element)
import File exposing (File)
import Http exposing (Error)
import FileManager.Vec exposing (..)
import Dict exposing (Dict)

type alias Flags =
  { api: String
  , thumbnailsUrl: String
  , downloadsUrl: String
  , dir: String
  , hasWriteRights : Bool
  }

type ViewMode = ListView | GridView

type SortBy = FileName | FileSize | FileDate | FileRights

type SortOrder = Asc | Desc

type alias Filters =
  { filter : String
  , sortBy : SortBy
  , sortOrder : SortOrder
  , opened : List String
  }

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
  , viewMode : ViewMode
  , filters  : Filters
  , tree     : Dict String TreeItem
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
  | Uploaded (Result Http.Error ())
  | OpenNameDialog DialogAction
  | CloseNameDialog
  | ConfirmNameDialog
  | FileUpdate FileUpdateError
  | Name String
  | Download
  | Cut
  | Paste
  | Delete
  | UpdateApiPath String
  | None
  | ChangeViewMode ViewMode
  | UpdateFilters Filters

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