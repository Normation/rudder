module  ApiCalls exposing (..)

import DataTypes exposing (..)
import Dict
import Http exposing (..)
import JsonDecoder exposing (..)
import JsonEncoder exposing (..)
import Json.Decode


--
-- This files contains all API calls for the technique editor
-- Summary:
-- GET    /internal/techniques : get the list of techniques (from technique editor)
-- GET    /internal/techniques/categories : get the list of techniques categories (all categories from lib)
-- GET    /internal/methods : get the list of available generic methods
-- PUT    /internal/techniques : create a new technique (error if existing)
-- POST   /internal/techniques : update an existing technique (error if doesn't exist yet)
-- DELETE /internal/techniques/${id}/${version} : delete given technique's version
-- GET    /internal/techniques/${id}/${version}/resources : get resources for an existing technique
-- GET    /internal/techniques/draft/${id}/1.0/resources : get resources for a newly created technique
-- GET    /internal/techniques/draft/${id}/${version}/resources : get resources for a newly cloned technique


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
        , url     = getUrl model "internal/techniques"
        , body    = emptyBody
        , expect  = expectJson GetTechniques ( Json.Decode.at ["data", "techniques" ] ( Json.Decode.list decodeTechnique))
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
        , url     = getUrl model "internal/techniques/categories"
        , body    = emptyBody
        , expect  = expectJson GetCategories ( Json.Decode.at ["data", "techniqueCategories" ] ( decodeCategory))
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
        , url     = getUrl model "internal/methods"
        , body    = emptyBody
        , expect  = expectJson GetMethods ( Json.Decode.at ["data", "methods" ] ( Json.Decode.map (Dict.fromList) (Json.Decode.keyValuePairs decodeMethod) ))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveTechnique : Technique -> Bool -> Model ->  Cmd Msg
saveTechnique  technique creation model =
  let
    req =
      request
        { method  = if creation then "PUT" else "POST"
        , headers = []
        , url     = getUrl model "internal/techniques"
        , body    = encodeTechnique technique |> jsonBody
        , expect  = expectJson SaveTechnique ( Json.Decode.at ["data", "techniques", "technique" ] ( decodeTechnique ))
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
        , url     = getUrl model "internal/techniques/" ++ technique.id.value ++ "/" ++ technique.version
        , body    = emptyBody
        , expect  = expectJson DeleteTechnique ( Json.Decode.at ["data", "techniques" ] ( decodeDeleteTechniqueResponse ))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getRessources : TechniqueState ->  Model -> Cmd Msg
getRessources state model =
  let
    url = case state of
            Edit t -> t.id.value ++ "/" ++ t.version ++ "/resources"
            Creation id -> "draft/" ++ id.value ++ "/" ++ "1.0/resources"
            Clone t id -> "draft/" ++ id.value ++ "/" ++ t.version ++ "/resources"
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model "internal/techniques/" ++ url
        , body    = emptyBody
        , expect  = expectJson GetTechniqueResources ( Json.Decode.at ["data", "resources" ] ( Json.Decode.list decodeResource ))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req
