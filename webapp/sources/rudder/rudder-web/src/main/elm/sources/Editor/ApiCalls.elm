module Editor.ApiCalls exposing (..)

import Dict
import Http exposing (..)
import Http.Detailed as Detailed
import Json.Decode exposing (list)
import Json.Encode exposing (object, string)

import Editor.DataTypes exposing (..)
import Editor.JsonDecoder exposing (..)
import Editor.JsonEncoder exposing (..)
import List.Extra
import Url.Builder exposing (QueryParameter)


--
-- This files contains all API calls for the technique editor
-- Summary:
-- GET    /techniques : get the list of techniques (from technique editor)
-- GET    /techniques/categories : get the list of techniques categories (all categories from lib)
-- GET    /methods : get the list of available generic methods
-- PUT    /techniques : create a new technique (error if existing)
-- POST   /techniques : update an existing technique (error if doesn't exist yet)
-- DELETE /techniques/${id}/${version} : delete given technique's version
-- GET    /techniques/${id}/${version}/resources : get resources for an existing technique
-- GET    /techniques/draft/${id}/1.0/resources : get resources for a newly created technique
-- GET    /techniques/draft/${id}/${version}/resources : get resources for a newly cloned technique
-- GET    /directives : list all directives

getUrl: Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api/" ++ url

getUrlNew: Model -> List String -> List QueryParameter -> String
getUrlNew m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api" :: url) p

getTechniques : Model -> Cmd Msg
getTechniques  model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model "techniques"
        , body    = emptyBody
        , expect  = Detailed.expectJson GetTechniques ( Json.Decode.at ["data", "techniques" ] (Json.Decode.map (List.filterMap identity) (Json.Decode.list (decodeTechniqueMaybe))))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req


getTechniqueYaml : Model -> Technique -> Cmd Msg
getTechniqueYaml  model technique =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model "techniques/"++technique.id.value++"/"++technique.version++"?format=yaml"
        , body    = emptyBody
        , expect  = Detailed.expectJson GetYaml ( Json.Decode.at ["data", "techniques" ] (headList  (Json.Decode.list (Json.Decode.at ["content"] Json.Decode.string))))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

checkTechnique : CheckMode -> Model -> Cmd Msg
checkTechnique mode model =
  let
    techniqueDecoder =  Json.Decode.at ["data", "techniques" ] (headList  (Json.Decode.list decodeTechnique))
    yamlDecoder = Json.Decode.at ["data", "techniques" ] (headList  (Json.Decode.list (Json.Decode.at ["output"] Json.Decode.string)))
    (input,output,(expect, body))=
      case mode of
        Import content -> ("yaml","json",(Detailed.expectJson (CheckOutJson mode) techniqueDecoder, stringBody "application/x-yml" content ))
        EditYaml content -> ("yaml", "json",(Detailed.expectJson (CheckOutJson mode) techniqueDecoder, stringBody "application/x-yml" content))
        CheckJson technique -> ("json", "yaml",(Detailed.expectJson (CheckOutYaml mode) yamlDecoder,  jsonBody (encodeTechnique technique)))
    req =
      request
        { method  = "POST"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model "techniques/check?input="++input++"&output="++output
        , body    =  body
        , expect  = expect
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req
getTechniquesCategories : Model -> Cmd Msg
getTechniquesCategories model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model "techniques/categories"
        , body    = emptyBody
        , expect  = Detailed.expectJson GetCategories ( Json.Decode.at ["data", "techniqueCategories" ] ( decodeCategory))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getMethods : Model -> Cmd Msg
getMethods  model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model "methods"
        , body    = emptyBody
        , expect  = Detailed.expectJson GetMethods ( Json.Decode.at ["data", "methods" ] ( Json.Decode.map (Dict.fromList) (Json.Decode.keyValuePairs decodeMethod) ))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveTechnique : Technique -> Bool -> Maybe TechniqueId -> Model ->  Cmd Msg
saveTechnique  technique creation internalId model  =
  let
    encoder =
      case internalId of
        Just value -> if(creation) then encodeNewTechnique technique value else encodeTechnique technique
        Nothing -> encodeTechnique technique
    req =
      request
        { method  = if creation then "PUT" else "POST"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model "techniques" ++ (if creation then "" else "/"++technique.id.value++"/"++technique.version)
        , body    = encoder |> jsonBody
        , expect  = Detailed.expectJson SaveTechnique ( Json.Decode.at ["data", "techniques" ] ( list decodeTechnique ) |> headList)
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req


deleteTechnique : Technique  -> Model ->  Cmd Msg
deleteTechnique  technique model =
  let
    req =
      request
        { method  = "DELETE"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model "techniques/" ++ technique.id.value ++ "/" ++ technique.version
        , body    = emptyBody
        , expect  = Detailed.expectJson DeleteTechnique ( Json.Decode.at ["data", "techniques" ] (list decodeDeleteTechniqueResponse ) |> headList)
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getRessources : TechniqueState ->  Model -> Cmd Msg
getRessources state model =
  let
    url = case state of
            Edit t -> "techniques/" ++ t.id.value ++ "/" ++ t.version ++ "/resources"
            Creation id -> "drafts/" ++ id.value ++ "/" ++ "1.0/resources"
            Clone t _ id -> "drafts/" ++ id.value ++ "/" ++ t.version ++ "/resources"
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model url
        , body    = emptyBody
        , expect  = Detailed.expectJson GetTechniqueResources ( Json.Decode.at ["data", "resources" ] ( Json.Decode.list decodeResource ))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req


copyResourcesToDraft : String -> Technique -> Maybe TechniqueId ->  Model -> Cmd Msg
copyResourcesToDraft draftId technique optId model =
  let
    id = Maybe.withDefault technique.id optId
    url = getUrlNew model [  "drafts", draftId, technique.version, "resources",  "clone"] [Url.Builder.string "techniqueId" id.value, Url.Builder.string "category" technique.category]
    req =
      request
        { method  = "POST"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = url
        , body    = emptyBody
        , expect  = Detailed.expectWhatever CopyResources
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getDirectives : Model -> Cmd Msg
getDirectives model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model "directives"
        , body    = emptyBody
        , expect  = Detailed.expectJson GetDirectives ( Json.Decode.at ["data", "directives" ] (Json.Decode.list decodeDirective))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req