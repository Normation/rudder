module FileManager.Action exposing (..)

import File exposing (File)
import Json.Decode as Decode exposing (succeed)
import Json.Decode exposing (Decoder)
import Http exposing (Body, emptyBody, expectJson, header, request, stringBody)
import Json.Decode.Pipeline exposing (required)
import Json.Encode
import List exposing ( map)
import FileManager.Model exposing (..)
import String exposing (dropLeft)
import Url.Builder exposing (string, toQuery, QueryParameter)

get :  String -> Decoder a -> (Result Http.Error a -> msg) -> Cmd msg
get url decoder handler =
  request
    { method = "GET"
    , headers = []
    , url = url
    , body = emptyBody
    , expect = expectJson handler decoder
    , timeout = Nothing
    , tracker = Nothing
    }

post :  String -> Body -> Decoder a -> (Result Http.Error a -> msg) -> Cmd msg
post  url body decoder handler =
  request
    { method = "POST"
    , headers = []
    , url = url
    , body = body
    , expect = expectJson handler decoder
    , timeout = Nothing
    , tracker = Nothing
    }

upload : String -> String -> File -> Cmd Msg
upload api dir file =
  request
    { method = "POST"
    , headers = []
    , url = api
    , body = Http.multipartBody [ Http.stringPart "destination" dir, Http.filePart "file" file ]
    , expect = Http.expectWhatever Uploaded
    , timeout = Nothing
    , tracker = Just "upload"
    }

listDirectory : String -> String -> Cmd Msg
listDirectory api dir =
  let
    body =  Json.Encode.object [
              ("action", Json.Encode.string "list")
            , ("path", Json.Encode.string dir)
            ]
  in
  post
    api
    (Http.jsonBody body )
    (Decode.at ["result"] (Decode.list fileDecoder))
    (EnvMsg << LsGotten)

fileDecoder : Decode.Decoder FileMeta
fileDecoder =
  succeed FileMeta
    |> required "name" Json.Decode.string
    |> required "type" Json.Decode.string
    |> required "size" Json.Decode.int
    |> required "date" Json.Decode.string
    |> required "rights" Json.Decode.string

move : String -> String -> List FileMeta -> String -> Cmd Msg
move api srcDir files dstDir =

  let
    filePath = files |> List.map (.name >> (++) srcDir )
    body =  Json.Encode.object [
              ("action", Json.Encode.string "move")
            , ("items", Json.Encode.list  Json.Encode.string filePath)
            , ("newPath", Json.Encode.string dstDir)
            ]
  in
  post
  api
  (Http.jsonBody body )
  (Decode.succeed ())
  (EnvMsg << Refresh)

encodeFiles : List FileMeta -> List QueryParameter
encodeFiles = map (string "files" << .name)

delete : String  -> String -> List FileMeta -> Cmd Msg
delete api dir files =
  let
    filePath = files |> List.map (.name >> (++) dir )
    body =  Json.Encode.object [
              ("action", Json.Encode.string "remove")
            , ("items", Json.Encode.list  Json.Encode.string filePath)
            ]
  in
  post
  api
  (Http.jsonBody body )
  (Decode.succeed ())
  (EnvMsg << Refresh)

newFile : String  -> String -> String -> Cmd Msg
newFile api dir name =
  let
    filePath = dir ++ "/" ++ name
    body =  Json.Encode.object [
              ("action", Json.Encode.string "createFile")
            , ("newPath", Json.Encode.string filePath)
            ]
  in
  post
  api
  (Http.jsonBody body )
  (Decode.succeed ())
  (EnvMsg << Refresh)

newDir : String  -> String -> String -> Cmd Msg
newDir api dir name =
  let
    filePath = dir ++ "/" ++ name
    body =  Json.Encode.object [
              ("action", Json.Encode.string "createFolder")
            , ("newPath", Json.Encode.string filePath)
            ]
  in
  post
  api
  (Http.jsonBody body )
  (Decode.succeed ())
  (EnvMsg << Refresh)

rename : String -> String -> String -> String -> Cmd Msg
rename api dir oldName newName =

  let
    body =  Json.Encode.object [
              ("action", Json.Encode.string "rename")
            , ("item", Json.Encode.string (dir ++ "/" ++ oldName))
            , ("newItemPath", Json.Encode.string (dir ++ "/" ++newName))
            ]
  in
  post
  api
  (Http.jsonBody body )
  (Decode.succeed ())
  (EnvMsg << Refresh)
