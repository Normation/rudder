module FileManager.Util exposing (..)

import FileManager.Model exposing (UploadResponse)
import FileManager.Port exposing (errorNotification)
import Html exposing (Attribute, Html)
import Html.Attributes exposing (type_)
import Array exposing (Array, fromList, get, toList)
import Http
import Http.Detailed as Detailed
import Json.Decode
import List exposing (drop, indexedMap, map2, take)

-- List

at : List a -> Int -> Maybe a
at list index = get index <| fromList list

set : Int -> a -> List a -> List a
set index item list = toList <| Array.set index item <| fromList list

remove : Int -> List a -> List a
remove index list = take index list ++ drop (index + 1) list

zip : List a -> List b -> List (a, b)
zip = map2 <| \x y -> (x, y)

indexedZip : List a -> List (Int, a)
indexedZip = indexedMap <| \x y -> (x, y)

swap : Int -> Int -> List a -> List a
swap index1 index2 list = toList <| arraySwap index1 index2 <| fromList list

arraySwap : Int -> Int -> Array a -> Array a
arraySwap index1 index2 array =
  let
    maybe1 = get index1 array
    maybe2 = get index2 array
  in case (maybe1, maybe2) of
    (Just item1, Just item2) -> Array.set index1 item2 <| Array.set index2 item1 array
    _ -> array

-- Maybe

isJust : Maybe a -> Bool
isJust maybe = case maybe of
  Just _ -> True
  Nothing -> False

-- Component

button : List (Attribute msg) -> List (Html msg) -> Html msg
button atts childs = Html.button (type_ "button" :: atts) childs

getDirPath : List String -> String
getDirPath dir =
  String.replace "//" "/" ((String.join "/" dir) ++ "/")

processApiError : String -> Http.Error -> Cmd msg
processApiError apiName err =
  let
    message =
      case err of
        Http.BadUrl url ->
            "The URL " ++ url ++ " was invalid"
        Http.Timeout ->
            "Unable to reach the server, try again"
        Http.NetworkError ->
            -- Firefox behavior is causing a network error with NS_ERROR_NET_RESET code, so we need to anticipate for both
            "You are trying to upload a file that exceeds the maximum upload size, or we are unable to reach the server, so please check your network connection."
        Http.BadStatus 500 ->
            "The server had a problem, try again later"
        Http.BadStatus 400 ->
            "Verify your information and try again"
        Http.BadStatus _ ->
            "Unknown error"
        Http.BadBody errorMessage ->
            errorMessage

  in
    errorNotification ("Error when " ++ apiName ++ ", details: \n" ++ message )

processApiDetailedError : String -> Json.Decode.Decoder UploadResponse -> Detailed.Error String -> Cmd msg
processApiDetailedError apiName decoder err =
  let
    formatApiNameMessage msg = "Error when "++ apiName ++ " : \n" ++ msg
    message =
      case err of
        Detailed.BadUrl url ->
          formatApiNameMessage ("The URL " ++ url ++ " was invalid")
        Detailed.Timeout ->
          formatApiNameMessage ("Unable to reach the server, try again")
        Detailed.NetworkError ->
          formatApiNameMessage ("You are trying to upload a file that exceeds the maximum upload size, or we are unable to reach the server, so please check your network connection.")
        Detailed.BadStatus metadata body ->
          case Json.Decode.decodeString decoder body of
            Ok { error } -> formatApiNameMessage (Maybe.withDefault "Unknown error" error)
            Err _ -> formatApiNameMessage "Unable to parse server response"
        Detailed.BadBody metadata body msg ->
          formatApiNameMessage msg
  in
    errorNotification message
