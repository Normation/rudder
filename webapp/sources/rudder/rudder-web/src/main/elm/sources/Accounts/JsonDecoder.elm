module Accounts.JsonDecoder exposing (..)

import Accounts.DataTypes as TenantMode exposing (..)
import Accounts.DataTypes as Token exposing (..)
import Accounts.DataTypes as TokenState exposing (..)
import Json.Decode exposing (..)
import Json.Decode.Extra
import Json.Decode.Pipeline exposing (..)
import List exposing (drop, head)
import String exposing (join, split)
import Time exposing (Posix, Zone)
import Time.DateTime
import Time.Extra
import Time.Iso8601
import Time.Iso8601ErrorMsg



-- GENERAL


decodeGetAccounts : Zone -> Decoder ApiResult
decodeGetAccounts zone =
    at [ "data" ] (decodeResult zone)


decodeAccount : Zone -> Decoder Account
decodeAccount zone =
    succeed Account
        |> required "id" string
        |> required "name" string
        |> required "description" string
        |> required "authorizationType" decodeAuthorizationType
        |> optional "kind" string "public"
        |> required "status" decodeAccountStatus
        |> required "creationDate" decodeDatetime
        |> required "tokenState" decodeTokenState
        |> optional "token" (maybe decodeToken) Nothing
        |> optional "tokenGenerationDate" (maybe decodeDatetime) Nothing
        |> custom decodeExpirationPolicy
        |> optional "lastAuthenticationDate" (maybe decodeDatetime) Nothing
        |> optional "acl" (map Just (list decodeAcls |> map List.concat)) Nothing
        |> required "tenants" (string |> andThen toTenantMode)
        |> required "tenants" (string |> andThen toTenantList)

decodeAuthorizationType : Decoder AuthorizationType
decodeAuthorizationType =
    string
        |> andThen
            (\s ->
                case s of
                    "ro" ->
                        succeed RO

                    "rw" ->
                        succeed RW

                    "none" ->
                        succeed None

                    "acl" ->
                        succeed ACL

                    _ ->
                        fail "Authorization status invalid, expected \"ro\", \"rw\", \"none\", or \"acl\"."
            )



decodeAccountStatus : Decoder AccountStatus
decodeAccountStatus =
    string
        |> andThen
            (\s ->
                case s of
                    "enabled" ->
                        succeed Enabled

                    "disabled" ->
                        succeed Disabled

                    _ ->
                        fail "Enabled status invalid, expected \"enabled\" or \"disabled\"."
            )


-- this one is used to talk to the Rudder API
-- we flatten the possible several actions into a list of unit ACL which is what is understood by UI
-- It's why in the JSON, we have [actions] and in elm we have "verb"
decodeAcls : Decoder (List AccessControl)
decodeAcls =
  let
    path = field "path" string
    actions = field "actions" (list string)
    acls = map2 (\p -> \l -> List.map (\a -> AccessControl p a) l) path actions
  in
    acls

-- this one is used to talk to the JS port
decodePortAcl : Decoder AccessControl
decodePortAcl =
    succeed AccessControl
        |> required "path" string
        |> required "verb" string


decodeExpirationPolicy : Decoder ExpirationPolicy
decodeExpirationPolicy =
    field "expirationPolicy" string
        |> andThen parseExpirationPolicy


parseExpirationPolicy : String -> Decoder ExpirationPolicy
parseExpirationPolicy str =
    case str of
        "never" ->
            succeed NeverExpire

        "datetime" ->
            field "expirationDate" decodeDatetime
                |> map ExpireAtDate

        _ ->
            fail "Unrecognized \"expirationPolicy\" field, expected \"never\" or \"datetime\""


parseToken : String -> ( Token )
parseToken str =
  case str of
    "2" -> Token.Hashed
    "1" -> Token.ClearText
    _   -> Token.New str


decodeToken : Decoder Token
decodeToken =
  andThen (\s -> succeed (parseToken s)) string

parseTokenState: String -> TokenState
parseTokenState str =
  case str of
    "generatedv1" -> TokenState.GeneratedV1
    "generatedv2" -> TokenState.GeneratedV2
    _             -> TokenState.Undef

decodeTokenState : Decoder TokenState
decodeTokenState =
  andThen (\s -> succeed (parseTokenState s)) string


-- the string for a tenant mode is '*', '-', or a comma separated list of non-empty string

parseTenants : String -> ( TenantMode, Maybe (List String) )
parseTenants str =
    let
        listToTenantMode =
            \l ->
                case l of
                    [] ->
                        ( TenantMode.NoAccess, Nothing )

                    nonEmptyList ->
                        ( ByTenants, Just nonEmptyList )
    in
    case str of
        "*" ->
            ( TenantMode.AllAccess, Nothing )

        "-" ->
            ( TenantMode.NoAccess, Nothing )

        tenantIds ->
            String.split "," tenantIds |> listToTenantMode


toTenantMode : String -> Decoder TenantMode
toTenantMode str =
    succeed (Tuple.first (parseTenants str))


toTenantList : String -> Decoder (Maybe (List String))
toTenantList str =
    succeed (Tuple.second (parseTenants str))


decodeResult : Zone -> Decoder ApiResult
decodeResult zone =
    succeed ApiResult
        |> required "accounts" (list (decodeAccount zone))


decodePostAccount : Zone -> Decoder Account
decodePostAccount zone =
    at [ "data", "accounts" ] (index 0 (decodeAccount zone))


decodeErrorDetails : String -> ( String, String )
decodeErrorDetails json =
    let
        errorMsg =
            decodeString (Json.Decode.at [ "errorDetails" ] string) json

        msg =
            case errorMsg of
                Ok s ->
                    s

                Err _ ->
                    "fail to process errorDetails"

        errors =
            split "<-" msg

        title =
            head errors
    in
    case title of
        Nothing ->
            ( "", "" )

        Just s ->
            ( s, join " \n " (drop 1 (List.map (\err -> "\t ‣ " ++ err) errors)) )



decodeDatetime : Decoder Posix
decodeDatetime = Json.Decode.Extra.datetime
