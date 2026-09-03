module Tags.Model exposing (..)


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
