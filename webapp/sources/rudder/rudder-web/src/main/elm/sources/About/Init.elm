module About.Init exposing (..)

import About.DataTypes exposing (..)
import About.ApiCalls exposing (apiGetAboutInfo)


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none

init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
    let
      initModel = Model flags.contextPath Nothing (UI True True True)
    in
      ( initModel
      , apiGetAboutInfo initModel
      )
{--
fakeData : AboutInfo
fakeData =
    let
        relaysList =
            [ (Relay "0123-4567-8901-2345" "relay1" 96)
            , (Relay "4567-8901-2345-6789" "relay2" 202)
            , (Relay "8901-2345-6789-0123" "relay3" 29)
            ]

        rudderInfo = RudderInfo "8.3.0" "build-1" "d40076ff-6a7d-4887-b1a9-6c99c4b25e29" relaysList

        os = OperatingSystem "Ubuntu" "24.04"
        jvm = JvmInfo "9.0" "lauchOptions -X -R --param1 --param2 --param3 --param4 --param5"
        system = SystemInfo os jvm

        nodes = NodesInfo 1000 600 400 900 100

        license = LicenseInfo "Demo Normation" "01-01-2024" "01-01-2025" 9999 "8.3.0"
        plugins =
            [ PluginInfo "branding" "Branding" "2.0.0" "8.3.0" license
            ]
    in
        AboutInfo rudderInfo system nodes plugins
--}
