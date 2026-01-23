module Accounts.JsonEncoder exposing (..)

import Accounts.DataTypes exposing (..)
import Iso8601
import Json.Encode exposing (..)
import Json.Encode.Extra exposing (maybe)
import Maybe.Extra
import String.Extra
import Time exposing (Month(..), Posix, Zone)


encodeAccount : Zone -> Account -> Value
encodeAccount zone account =
    let
        ( expirationPolicy, expirationDate ) =
            case account.expirationPolicy of
                ExpireAtDate d ->
                    ( "datetime", [ ( "expirationDate", string (Iso8601.fromTime d) ) ] )

                NeverExpire ->
                    ( "never", [] )

        acl =
            case account.acl of
                Just a ->
                    [ ( "acl", list encodeAcl a ) ]

                Nothing ->
                    []

        status =
            accountStatusText account.status

        authorizationType =
            authorizationTypeText account.authorizationType

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
         , ( "authorizationType", string authorizationType )
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

