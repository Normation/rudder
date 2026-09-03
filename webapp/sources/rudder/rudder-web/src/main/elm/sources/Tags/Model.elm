module Tags.Model exposing (Completion(..), CompletionValue, Model, Tag, UI, initModel)


type alias Model =
    { contextPath : String
    , ui : UI
    , newTag : Tag
    , tags : List Tag
    }


type alias Tag =
    { key : String
    , value : String
    }


type alias UI =
    { hasWriteRights : Bool
    , isEditForm : Bool
    , objectType : String
    , completionKeys : List CompletionValue
    , completionValues : List CompletionValue
    , filterTags : List Tag
    }


type alias CompletionValue =
    { value : String
    }


type Completion
    = Key
    | Val


initTag : Tag
initTag =
    Tag "" ""


initUi : { a | hasWriteRights : Bool, isEditForm : Bool, objectType : String } -> UI
initUi flags =
    { hasWriteRights = flags.hasWriteRights
    , isEditForm = flags.isEditForm
    , objectType = flags.objectType
    , completionKeys = []
    , completionValues = []
    , filterTags = []
    }


initModel : { a | contextPath : String, hasWriteRights : Bool, isEditForm : Bool, objectType : String, tags : List Tag } -> Model
initModel flags =
    { contextPath = flags.contextPath
    , ui = initUi flags
    , newTag = initTag
    , tags = flags.tags
    }
