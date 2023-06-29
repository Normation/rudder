module FileManager.Model exposing (..)

import Browser.Dom exposing (Element)
import File exposing (File)
import Http exposing (Error)
import FileManager.Vec exposing (..)

type alias Flags =
  { api: String
  , thumbnailsUrl: String
  , downloadsUrl: String
  , dir: String
  , hasWriteRights : Bool
  }

type alias Model =
  { api: String
  , thumbnailsUrl: String
  , downloadsUrl: String
  , dir: String
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
  | Name String
  | Download
  | Cut
  | Paste
  | Delete
  | UpdateApiPath String
  | None

type EnvMsg
  = Open ()
  | Close
  | Accept
  | MouseDown (Maybe FileMeta) Vec2 Bool
  | GetBounds (Result Browser.Dom.Error (List Element))
  | MouseMove Vec2
  | MouseUp (Maybe FileMeta) Int
  | GetLs String
  | LsGotten (Result Error (List FileMeta)) 
  | Refresh (Result Error ())

type DialogAction = Rename FileMeta String | NewFile String | NewDir String | Closed