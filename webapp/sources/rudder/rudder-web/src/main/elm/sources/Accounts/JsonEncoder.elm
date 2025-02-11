module Accounts.JsonEncoder exposing (..)

import Accounts.DataTypes exposing (..)
import Accounts.DatePickerUtils exposing (posixToString)
import Json.Encode exposing (..)
import Json.Encode.Extra exposing (maybe)
import Maybe.Extra


encodeAccount : DatePickerInfo -> Account -> Value
encodeAccount datePickerInfo account =
    let
        ( expirationDate, expirationPolicy ) =
            case account.expirationDate of
                Just d ->
                    ( [ ( "expirationDate", string (posixToString datePickerInfo d) ) ], "datetime" )

                Nothing ->
                    ( [], "never" )

        acl =
            case account.acl of
                Just a ->
                    [ ( "acl", list encodeAcl a ) ]

                Nothing ->
                    []
        status =
          case account.enabled of
            True -> "enabled"
            False -> "disabled"
        id = case String.isEmpty account.id of
            True -> Nothing
            False -> Just account.id
    in
    object
        ([ ( "id", maybe string id)
         , ( "name", string account.name )
         , ( "description", string account.description )
         , ( "status", string status )
         , ( "tenants", string (encodeTenants account.tenantMode account.selectedTenants) )
         , ( "generateToken", bool (Maybe.Extra.isNothing id))
         , ( "authorizationType", string account.authorisationType )
         , ( "expirationPolicy", string expirationPolicy )
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
