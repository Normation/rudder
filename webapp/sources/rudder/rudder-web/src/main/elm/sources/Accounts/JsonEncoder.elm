module Accounts.JsonEncoder exposing (..)

import Accounts.DataTypes exposing (..)
import Json.Encode exposing (..)
import Json.Encode.Extra exposing (maybe)
import Maybe.Extra
import String.Extra
import Time exposing (Month(..), Posix, Zone)
import Time.DateTime exposing (..)
import Time.Extra
import Time.Iso8601


encodeAccount : DatePickerInfo -> Account -> Value
encodeAccount { zone } account =
    let
        ( expirationPolicy, expirationDate ) =
            case account.expirationPolicy of
                ExpireAtDate d ->
                    ( "datetime", [ ( "expirationDate", string (posixToIso8601 zone d) ) ] )

                NeverExpire ->
                    ( "never", [] )

        acl =
            case account.acl of
                Just a ->
                    [ ( "acl", list encodeAcl a ) ]

                Nothing ->
                    []

        status =
            if account.enabled then
                "enabled"

            else
                "disabled"

        id =
            String.Extra.nonEmpty account.id
    in
    object
        ([ ( "id", maybe string id )
         , ( "name", string account.name )
         , ( "description", string account.description )
         , ( "status", string status )
         , ( "tenants", string (encodeTenants account.tenantMode account.selectedTenants) )
         , ( "generateToken", bool (Maybe.Extra.isNothing id) )
         , ( "authorizationType", string account.authorisationType )
         , ( "expirationPolicy", string expirationPolicy )
         ]
            |> List.append expirationDate
            |> List.append acl
        )


-- this one is used to talk to the Rudder API
-- we don't need to group ACL on the same path for different verbs, the scala part will do that for us.
encodeAcl : AccessControl -> Value
encodeAcl acl =
    object
        [ ( "path", string acl.path )
        , ( "actions", list string [acl.verb] ) -- in elm UI, we have only pair of (path, verb) and json, (path, [actions])
        ]

-- this one is used to talk to the JS port
encodePortAcl : AccessControl -> Value
encodePortAcl acl =
    object
        [ ( "path", string acl.path )
        , ( "verb", string acl.verb )
        ]

encodeTokenAcl : String -> List AccessControl -> Value
encodeTokenAcl tokenId acl =
    object
        [ ( "id", string tokenId )
        , ( "acl", list encodePortAcl acl )
        ]


encodeTenants : TenantMode -> Maybe (List String) -> String
encodeTenants mode selected =
    case mode of
        AllAccess ->
            "*"

        NoAccess ->
            "-"

        ByTenants ->
            case selected |> Maybe.withDefault [] of
                [] ->
                    "-"

                l ->
                    String.join "," l


encodeAccountTenants : String -> List String -> Value
encodeAccountTenants accountId tenants =
    object
        [ ( "id", string accountId )
        , ( "tenants", list string tenants )
        ]


{-| Take a zone into account when encoding to date to ISO8601 from a POSIX.
We need to translate into parts, otherwise we would lose zone information.
-}
posixToIso8601 : Zone -> Posix -> String
posixToIso8601 zone p =
    let
        { year, month, day, hour, minute, second, millisecond } =
            Time.Extra.posixToParts zone p
    in
    Time.DateTime.fromPosix p
        |> setYear year
        |> setMonth (monthToInt month)
        |> setDay day
        |> setHour hour
        |> setMinute minute
        |> setSecond second
        |> setMillisecond millisecond
        |> Time.Iso8601.fromDateTime


{-| Library does not expose this
-}
monthToInt : Month -> Int
monthToInt m =
    case m of
        Jan ->
            1

        Feb ->
            2

        Mar ->
            3

        Apr ->
            4

        May ->
            5

        Jun ->
            6

        Jul ->
            7

        Aug ->
            8

        Sep ->
            9

        Oct ->
            10

        Nov ->
            11

        Dec ->
            12
