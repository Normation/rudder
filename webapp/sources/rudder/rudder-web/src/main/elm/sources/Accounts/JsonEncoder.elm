module Accounts.JsonEncoder exposing (..)

import Accounts.DataTypes exposing (..)
import Accounts.DatePickerUtils exposing (posixToString)
import Json.Encode exposing (..)


encodeAccount : DatePickerInfo -> Account -> Value
encodeAccount datePickerInfo account =
    let
        ( expirationDate, expirationDateDefined ) =
            case account.expirationDate of
                Just d ->
                    ( [ ( "expirationDate", string (posixToString datePickerInfo d) ) ], account.expirationDateDefined )

                Nothing ->
                    ( [], False )

        acl =
            case account.acl of
                Just a ->
                    [ ( "acl", list encodeAcl a ) ]

                Nothing ->
                    []
    in
    object
        ([ ( "id", string account.id )
         , ( "name", string account.name )
         , ( "description", string account.description )
         , ( "authorizationType", string account.authorisationType )
         , ( "kind", string account.kind )
         , ( "enabled", bool account.enabled )
         , ( "creationDate", string account.creationDate )
         , ( "token", string account.token )
         , ( "tokenGenerationDate", string account.tokenGenerationDate )
         , ( "expirationDateDefined", bool expirationDateDefined )
         , ( "tenants", string (encodeTenants account.tenantMode account.selectedTenants) )
         ]
            |> List.append expirationDate
            |> List.append acl
        )


encodeAcl : AccessControl -> Value
encodeAcl acl =
    object
        [ ( "path", string acl.path )
        , ( "verb", string acl.verb )
        ]


encodeTokenAcl : String -> List AccessControl -> Value
encodeTokenAcl tokenId acl =
    object
        [ ( "id", string tokenId )
        , ( "acl", list encodeAcl acl )
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
