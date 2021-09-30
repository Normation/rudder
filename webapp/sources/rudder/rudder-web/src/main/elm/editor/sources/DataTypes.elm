module DataTypes exposing (..)

import Dict exposing (Dict)
import File exposing (File)
import Http exposing (Error)
import Json.Decode exposing (Value)
import MethodConditions exposing (..)
import Dom.DragDrop as DragDrop

--
-- All our data types
--

type alias TechniqueId = {value : String}

type alias MethodId = {value : String}

type alias CallId = {value : String}

type alias ParameterId = {value : String}



type AgentValue = Value String | Variable (List AgentValue)

type Constraint =
    AllowEmpty Bool
  | AllowWhiteSpace Bool
  | MaxLength Int
  | MinLength Int
  | MatchRegex String
  | NotMatchRegex String
  | Select (List String)

type alias MethodParameter =
  { name        : ParameterId
  , description : String
  , type_       : String
  , constraints : List Constraint
  }

type Agent = Cfengine | Dsc

type alias Method =
  { id             : MethodId
  , name           : String
  , description    : String
  , classPrefix    : String
  , classParameter : ParameterId
  , agentSupport   : List Agent
  , parameters     : List MethodParameter
  , documentation  : Maybe String
  , deprecated     : Maybe String
  , rename         : Maybe String
  }

type alias Technique =
  { id          : TechniqueId
  , version     : String
  , name        : String
  , description : String
  , category    : String
  , elems       : List MethodElem
  , parameters  : List TechniqueParameter
  , resources   : List Resource
  }

type MethodElem = Call (Maybe CallId) MethodCall | Block (Maybe CallId) MethodBlock



type ReportingLogic = WorstReport | SumReport | FocusReport String

type alias MethodBlock =
  { id : CallId
  , component : String
  , condition : Condition
  , reportingLogic : ReportingLogic
  , calls : List MethodElem
  }

type alias MethodCall =
  { id         : CallId
  , methodName : MethodId
  , parameters : List CallParameter
  , condition  : Condition
  , component  : String
  , disableReporting : Bool
  }

type alias CallParameter =
  { id    : ParameterId
  , value : List AgentValue
  }

type alias TechniqueParameter =
  { id          : ParameterId
  , name        : String
  , description : String
  , mayBeEmpty  : Bool
  }

type alias TechniqueCategory =
    { id : String
    , name : String
    , path : String
    , subCategories : SubCategories
    }
type SubCategories = SubCategories (List TechniqueCategory)

allCategories t =
  let subElems = case t.subCategories of SubCategories l -> List.concatMap allCategories l
  in t :: subElems

allCategorieswithoutRoot m =
  let subElems = case m.categories.subCategories of SubCategories l -> List.concatMap allCategories l
  in subElems

type TechniqueState = Creation TechniqueId | Edit Technique | Clone Technique TechniqueId

type ModalState = DeletionValidation Technique

type DragElement = NewMethod Method | NewBlock | Move MethodElem

type DropElement = StartList | AfterElem (Maybe CallId) MethodElem | InBlock MethodBlock

type alias Model =
  { techniques         : List Technique
  , methods            : Dict String Method
  , categories         : TechniqueCategory
  , mode               : Mode
  , contextPath        : String
  , techniqueFilter    : String
  , methodsUI          : MethodListUI
  , genericMethodsOpen : Bool
  , dnd                : DragDrop.State DragElement DropElement
  , modal              : Maybe ModalState
  , hasWriteRights     : Bool
  }

type ResourceState = New | Untouched | Deleted | Modified

type alias Resource =
  { name  : String
  , state : ResourceState
  }

type alias MethodListUI =
  { filter   : MethodFilter
  , docsOpen : List MethodId
  }

type alias MethodFilter =
  { name           : String
  , showDeprecated : Bool
  , agent          : Maybe Agent
  , state          : MethodFilterState
  }

type MethodFilterState = FilterOpened | FilterClosed
type ValidationState error = Unchanged | ValidState | InvalidState error
type TechniqueNameError = EmptyName | AlreadyTakenName
type TechniqueIdError = TooLongId | AlreadyTakenId | InvalidStartId
type MethodCallParamError = ConstraintError (List String)

type alias MethodCallUiInfo =
  { mode       : MethodCallMode
  , tab        : Maybe MethodCallTab
  , validation : Dict String  ( ValidationState MethodCallParamError )
  , showChildDetails : Bool
  }

type alias TechniqueUiInfo =
  { tab              : Tab
  , callsUI          : Dict String MethodCallUiInfo
  , openedParameters : List ParameterId
  , saving           : Bool
  , nameState        : ValidationState TechniqueNameError
  , idState          : ValidationState TechniqueIdError
  }

type MethodCallTab = CallParameters | Conditions | Result | Reporting
type MethodCallMode = Opened | Closed
type Tab = General |  Parameters | Resources | None
type Mode = Introduction | TechniqueDetails Technique TechniqueState TechniqueUiInfo

-- all events in the event loop
type Msg =
    SelectTechnique Technique
  | GetTechniques   (Result Error (List Technique))
  | SaveTechnique   (Result Error Technique)
  | UpdateTechnique Technique
  | DeleteTechnique (Result Error (TechniqueId, String))
  | GetTechniqueResources  (Result Error (List Resource))
  | GetCategories (Result Error  TechniqueCategory)
  | GetMethods   (Result Error (Dict String Method))
  | UIMethodAction CallId MethodCallUiInfo
  | RemoveMethod CallId
  | CloneMethod  MethodCall CallId
  | MethodCallParameterModified MethodCall ParameterId String
  | MethodCallModified MethodElem
  | TechniqueParameterModified ParameterId TechniqueParameter
  | TechniqueParameterRemoved ParameterId
  | TechniqueParameterAdded ParameterId
  | TechniqueParameterToggle ParameterId
  | GenerateId (String -> Msg)
  | SwitchTabMethod CallId MethodCallTab
  | CallApi  (Model -> Cmd Msg)
  | SwitchTab Tab
  | UpdateTechniqueFilter String
  | UpdateMethodFilter MethodFilter
  | ToggleDoc MethodId
  | OpenMethods
  | OpenTechniques
  | NewTechnique TechniqueId
  | Ignore
  | AddMethod Method CallId
  | AddBlock CallId
  | SetCallId CallId
  | StartSaving
  | Copy String
  | Store String Value
  | GetFromStore Technique (Maybe Technique) TechniqueId
  | CloneTechnique Technique TechniqueId
  | ResetTechnique
  | ResetMethodCall MethodCall
  | ToggleFilter
  | OpenDeletionPopup Technique
  | ClosePopup Msg
  | OpenFileManager
  | Export
  | StartImport
  | ImportFile File
  | ParseImportedFile File String
  | ScrollCategory String
  | MoveStarted DragElement
  | MoveTargetChanged DropElement
  | MoveCanceled
  | MoveCompleted DragElement DropElement
  | SetMissingIds String

dragDropMessages : DragDrop.Messages Msg DragElement DropElement
dragDropMessages =
  { dragStarted = MoveStarted
  , dropTargetChanged = MoveTargetChanged
  , dragEnded = MoveCanceled
  , dropped = MoveCompleted
  }