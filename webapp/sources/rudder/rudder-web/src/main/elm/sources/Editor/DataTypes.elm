module Editor.DataTypes exposing (..)

import Dict exposing (Dict)
import Either exposing (Either)
import File exposing (File)
import Http exposing (Error)
import Http.Detailed
import Dom.DragDrop as DragDrop
import Time exposing (Posix)
import Bytes exposing (Bytes)

import Editor.MethodConditions exposing (..)
--
-- All our data types
--

type alias TechniqueId = {value : String}

type alias MethodId = {value : String}

type alias CallId = {value : String}

type alias ParameterId = {value : String}

type alias DraftId = {value : String}

type alias Draft = { technique : Technique, origin : Maybe Technique, id : DraftId, date : Posix}

type AgentValue = Value String | Variable (List AgentValue)

type alias Constraint =
  { allowEmpty : Maybe Bool
  , allowWhiteSpace:  Maybe Bool
  , maxLength: Maybe Int
  , minLength: Maybe Int
  , matchRegex: Maybe String
  , notMatchRegex: Maybe String
  , select: Maybe (List SelectOption)
  }

type alias SelectOption =
  { value : String
  , name : Maybe String
  }

defaultConstraint = Constraint Nothing Nothing Nothing Nothing Nothing Nothing Nothing

type alias MethodParameter =
  { name        : ParameterId
  , description : String
  , type_       : String
  , constraints : Constraint
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

type alias CompilationOutput =
  { compiler:  String
  , resultCode: Int
  , msg:        String
  , stdout:     String
  , stderr:     String
  }

type alias Technique =
  { id            : TechniqueId
  , version       : String
  , name          : String
  , description   : String
  , documentation : String
  , category      : String
  , elems         : List MethodElem
  , parameters    : List TechniqueParameter
  , resources     : List Resource
  , tags          : List (String,String)
  , output        : Maybe CompilationOutput
  }

type MethodElem = Call (Maybe CallId) MethodCall | Block (Maybe CallId) MethodBlock


type WorstReportKind = WorstReportWeightedOne | WorstReportWeightedSum | FocusWorst

type ReportingLogic = WorstReport WorstReportKind | WeightedReport | FocusReport String

type PolicyMode = Audit | Enforce

type alias MethodBlock =
  { id : CallId
  , component : String
  , condition : Condition
  , reportingLogic : ReportingLogic
  , calls : List MethodElem
  , policyMode : Maybe PolicyMode
  }

type alias MethodCall =
  { id         : CallId
  , methodName : MethodId
  , parameters : List CallParameter
  , condition  : Condition
  , component  : String
  , disableReporting : Bool
  , policyMode : Maybe PolicyMode
  }

type alias CallParameter =
  { id    : ParameterId
  , value : List AgentValue
  }

type ParameterType = StringParameter | SelectParameter (List String)

type alias TechniqueParameter =
  { id          : ParameterId
  , name        : String
  , description : Maybe String
  , documentation : Maybe String
  , mayBeEmpty  : Bool
  , constraints : Constraint
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

type TechniqueState = Creation TechniqueId | Edit Technique | Clone Technique (Maybe TechniqueId) TechniqueId

type alias TechniqueCheckState =
  { id : TechniqueId
  , name : String
  }

type ModalState = DeletionValidation Technique

type DragElement = NewMethod Method | NewBlock | Move MethodElem

type DropElement = StartList | AfterElem (Maybe CallId) MethodElem | InBlock MethodBlock

type alias Model =
  { techniques         : List Technique
  , methods            : Dict String Method
  , categories         : TechniqueCategory
  , drafts             : Dict String Draft
  , mode               : Mode
  , contextPath        : String
  , techniqueFilter    : TreeFilters
  , methodsUI          : MethodListUI
  , genericMethodsOpen : Bool
  , dnd                : DragDrop.State DragElement DropElement
  , modal              : Maybe ModalState
  , hasWriteRights     : Bool
  , dropTarget         : Maybe DropElement
  , isMethodHovered    : Maybe MethodId
  , loadingTechniques  : Bool
  , recClone           : List Msg
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

type alias TreeFilters =
  { filter : String
  , folded : List String
  }


type MethodFilterState = FilterOpened | FilterClosed
type ValidationState error = Unchanged | ValidState | InvalidState (List error)
type TechniqueNameError = EmptyName | AlreadyTakenName
type BlockError = EmptyComponent  | NoFocusError | ConditionError
type TechniqueIdError = TooLongId | AlreadyTakenId
type MethodCallParamError = ConstraintError { id : ParameterId , message: String }
type MethodCallConditionError = ReturnCarrigeForbidden

type alias MethodCallUiInfo =
  { mode       : MethodCallMode
  , tab        : MethodCallTab
  , validation : ValidationState MethodCallParamError
  }
type alias MethodBlockUiInfo =
  { mode       : MethodCallMode
  , tab        : MethodBlockTab
  , validation : ValidationState BlockError
  , showChildDetails : Bool
  }

type alias TechniqueUiInfo =
  { tab              : Tab
  , callsUI          : Dict String MethodCallUiInfo
  , blockUI          : Dict String MethodBlockUiInfo
  , openedParameters : List ParameterId
  , saving           : Bool
  , nameState        : ValidationState TechniqueNameError
  , idState          : ValidationState TechniqueIdError
  , enableDragDrop   : Maybe CallId
  }

type alias TechniqueEditInfo =
  {  value : String
  ,  open : Bool
  ,  result : Result String ()
  }

type MethodCallTab = CallParameters | CallConditions | Result | CallReporting
type MethodBlockTab = BlockConditions | BlockReporting | Children
type MethodCallMode = Opened | Closed
type Tab = General | Parameters | Resources | Output | None
type Mode = Introduction | TechniqueDetails Technique TechniqueState TechniqueUiInfo TechniqueEditInfo

type CheckMode = Import String | EditYaml String | CheckJson Technique


-- all events in the event loop
type Msg =
    SelectTechnique (Either Technique Draft)
  | GetTechniques   (Result (Http.Detailed.Error String) ( Http.Metadata, List Technique ))
  | GetYaml         (Result (Http.Detailed.Error String) ( Http.Metadata, String ))
  | SaveTechnique   (Result (Http.Detailed.Error String) ( Http.Metadata, Technique ))
  | UpdateTechnique Technique
  | DeleteTechnique (Result (Http.Detailed.Error String) ( Http.Metadata, TechniqueId ))
  | GetTechniqueResources  (Result (Http.Detailed.Error String) ( Http.Metadata, List Resource ))
  | CopyResources  (Result (Http.Detailed.Error Bytes) ())
  | GetCategories (Result (Http.Detailed.Error String)  ( Http.Metadata, TechniqueCategory ))
  | GetMethods   (Result (Http.Detailed.Error String) ( Http.Metadata, (Dict String Method) ))
  | CheckOutJson CheckMode (Result (Http.Detailed.Error String) ( Http.Metadata, Technique ))
  | CheckOutYaml CheckMode (Result (Http.Detailed.Error String) ( Http.Metadata, String ))
  | UIMethodAction CallId MethodCallUiInfo
  | UIBlockAction CallId MethodBlockUiInfo
  | RemoveMethod CallId
  | UpdateEdition TechniqueEditInfo
  | CloneElem  MethodElem CallId
  | MethodCallParameterModified MethodCall ParameterId String
  | MethodCallModified MethodElem
  | TechniqueParameterModified ParameterId TechniqueParameter
  | TechniqueParameterRemoved ParameterId
  | TechniqueParameterAdded ParameterId
  | GenerateId (String -> Msg)
  | CallApi  (Model -> Cmd Msg)
  | SwitchTab Tab
  | UpdateTechniqueFilter TreeFilters
  | UpdateMethodFilter MethodFilter
  | ToggleDoc MethodId
  | ShowDoc MethodId
  | OpenMethods
  | OpenTechniques
  | NewTechnique TechniqueId
  | Ignore
  | AddMethod Method CallId
  | AddBlock CallId
  | SetCallId CallId
  | StartSaving
  | Copy String
  | GetDrafts (Dict String Draft) (List String)
  | CloneTechnique Technique (Maybe TechniqueId) TechniqueId
  | ResetTechnique
  | ResetMethodCall MethodElem
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
  -- Sometimes drag and drop event are blocked / catched, fire it manually
  | CompleteMove
  | FinalizeImport String
  | SetMissingIds String
  | Notification (String -> Cmd Msg) String
  | DisableDragDrop
  | EnableDragDrop CallId
  | HoverMethod (Maybe CallId)

dragDropMessages : DragDrop.Messages Msg DragElement DropElement
dragDropMessages =
  { dragStarted = MoveStarted
  , dropTargetChanged = MoveTargetChanged
  , dragEnded = MoveCanceled
  , dropped = MoveCompleted
  }


techniqueCheckState : Technique -> TechniqueCheckState
techniqueCheckState { id, name } = { id = id, name = name }


draftCheckState : Draft -> TechniqueCheckState
draftCheckState { id, technique } = { id = id, name = technique.name }