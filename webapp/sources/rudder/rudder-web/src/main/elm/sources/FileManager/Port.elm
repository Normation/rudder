port module FileManager.Port exposing (..)

port errorNotification : String -> Cmd msg
port onOpen : (() -> msg) -> Sub msg
port close : List String -> Cmd msg
port updateApiPAth : (String -> msg) -> Sub msg
