module FileManager.Action exposing (..)

import File exposing (File)
import Json.Decode as Decode exposing (andThen, bool, fail, maybe, succeed)
import Json.Decode exposing (Decoder)
import Http.Detailed
import Http exposing (Body, emptyBody, expectJson, header, request, stringBody)
import Json.Decode.Pipeline exposing (required, optional)
import Json.Encode
import List exposing ( map)
import List.Extra

import String exposing (dropLeft)
import Url.Builder exposing (string, toQuery, QueryParameter)

import FileManager.Model exposing (..)
import FileManager.Util exposing (getDirPath)
import Http exposing (expectBytesResponse)
import Bytes exposing (Bytes)
import Bytes.Decode

get :  String -> Decoder a -> (Result Http.Error a -> msg) -> Cmd msg
get url decoder handler =
  request
    { method = "GET"
    , headers = [header "X-Requested-With" "XMLHttpRequest"]
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
    , headers = [header "X-Requested-With" "XMLHttpRequest"]
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
    , headers = [header "X-Requested-With" "XMLHttpRequest"]
    , url = api
    , body = Http.multipartBody [ Http.stringPart "destination" dir, Http.filePart "file" file ]
    , expect = Http.Detailed.expectJson Uploaded decoderUploadResponse
    , timeout = Nothing
    , tracker = Just "upload"
    }

listDirectory : String -> List String -> Cmd Msg
listDirectory api dir =
  let

    body =  Json.Encode.object [
              ("action", Json.Encode.string "list")
            , ("path", Json.Encode.string (getDirPath dir))
            ]
  in
  post
    api
    (Http.jsonBody body )
    (Decode.at ["result"] (Decode.list fileDecoder))
    (EnvMsg << (LsGotten (getDirPath dir)))

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
  handleFileUpdate

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
  handleFileUpdate

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
  handleFileUpdate

headList : Decoder (List a) -> Decoder a
headList decoder =
  andThen ( \l ->
    case l of
      [] -> fail ""
      x :: _ -> succeed x
    ) decoder

download : String -> List String -> FileMeta -> Cmd Msg
download api dir file =
  let
    -- capture the bytes of the response if status is good, else just return the err
    resolve toResult response =
      case response of
        Http.BadUrl_ url_ ->
          Err (Http.BadUrl url_)
        Http.Timeout_ ->
          Err Http.Timeout
        Http.NetworkError_ ->
          Err Http.NetworkError
        Http.BadStatus_ metadata _ ->
          Err (Http.BadStatus metadata.statusCode)
        Http.GoodStatus_ _ body ->
          Result.mapError Http.BadBody (toResult body)
  in
    request
      { method = "GET"
      , headers = [header "X-Requested-With" "XMLHttpRequest"]
      , url = api ++ "?action=download&path=" ++ (getDirPath dir) ++ file.name
      , body = emptyBody
      , expect = expectBytesResponse (Downloaded file) (resolve Ok)
      , timeout = Nothing
      , tracker = Nothing
      }
  
  

getContent : String  -> String -> String -> Cmd Msg
getContent api dir name =
  let
    filePath = dir ++ "/" ++ name
    body =  Json.Encode.object [
              ("action", Json.Encode.string "getContent")
            , ("item", Json.Encode.string filePath)
            ]
  in
  post
  api
  (Http.jsonBody body )
  (Decode.at ["result"] Json.Decode.string)
  (EnvMsg << GotContent)

saveContent : String  -> String -> String -> String -> Cmd Msg
saveContent api dir name content =
  let
    filePath = dir ++ "/" ++ name
    body =  Json.Encode.object [
              ("action", Json.Encode.string "edit")
            , ("item", Json.Encode.string filePath)
            , ("content", Json.Encode.string content)
            ]
  in
  post
  api
  (Http.jsonBody body )
  (Decode.succeed ())
  handleFileUpdate


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
  handleFileUpdate

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
  handleFileUpdate


handleFileUpdate : Result Http.Error a -> Msg
handleFileUpdate r = case r of
  Ok _ -> EnvMsg (Refresh (Ok ()))
  Err e -> FileUpdate (FileUpdateHttpError e)

decoderUploadResponse : Decoder UploadResponse
decoderUploadResponse =
  succeed UploadResponse
    |> required "success" bool
    |> optional "error" (maybe Decode.string) Nothing
