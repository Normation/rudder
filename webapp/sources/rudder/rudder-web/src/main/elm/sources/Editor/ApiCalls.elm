module Editor.ApiCalls exposing (..)

import Dict
import Http exposing (..)
import Http.Detailed as Detailed
import Json.Decode
import Maybe.Extra

import Editor.DataTypes exposing (..)
import Editor.JsonDecoder exposing (..)
import Editor.JsonEncoder exposing (..)


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


getUrl: Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api/" ++ url

getTechniques : Model -> Cmd Msg
getTechniques  model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model "techniques"
        , body    = emptyBody
        , expect  = Detailed.expectJson GetTechniques ( Json.Decode.at ["data", "techniques" ] (Json.Decode.map (List.filterMap identity) (Json.Decode.list (decodeTechniqueMaybe))))
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
        , headers = []
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
        , headers = []
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
        , headers = []
        , url     = getUrl model "techniques" ++ (if creation then "" else "/"++technique.id.value++"/"++technique.version)
        , body    = encoder |> jsonBody
        , expect  = Detailed.expectJson SaveTechnique ( Json.Decode.at ["data", "techniques", "technique" ] ( decodeTechnique ))
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
        , headers = []
        , url     = getUrl model "techniques/" ++ technique.id.value ++ "/" ++ technique.version
        , body    = emptyBody
        , expect  = Detailed.expectJson DeleteTechnique ( Json.Decode.at ["data", "techniques" ] ( decodeDeleteTechniqueResponse ))
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
            Clone t id -> "drafts/" ++ id.value ++ "/" ++ t.version ++ "/resources"
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model url
        , body    = emptyBody
        , expect  = Detailed.expectJson GetTechniqueResources ( Json.Decode.at ["data", "resources" ] ( Json.Decode.list decodeResource ))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req
