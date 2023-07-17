port module FileManager.Port exposing (..)

port onOpen : (() -> msg) -> Sub msg
port close : List String -> Cmd msg
port updateApiPAth : (String -> msg) -> Sub msg
