module  JsonEncoder exposing (..)

import DataTypes exposing (..)
import Json.Encode exposing (..)
import Date
import DatePickerUtils exposing (posixToString)

encodeAccount : DatePickerInfo -> Account -> Value
encodeAccount datePickerInfo account =
  let
    (expirationDate, expirationDateDefined) = case account.expirationDate of
      Just d  -> ([("expirationDate", string (posixToString datePickerInfo d))], account.expirationDateDefined)
      Nothing -> ([], False)

    acl = case account.acl of
      Just a  -> [("acl", list encodeAcl a)]
      Nothing -> []
  in
    object (
      [ ( "id"                    , string account.id                  )
      , ( "name"                  , string account.name                )
      , ( "description"           , string account.description         )
      , ( "authorizationType"     , string account.authorisationType   )
      , ( "kind"                  , string account.kind                )
      , ( "enabled"               , bool account.enabled               )
      , ( "creationDate"          , string account.creationDate        )
      , ( "token"                 , string account.token               )
      , ( "tokenGenerationDate"   , string account.tokenGenerationDate )
      , ( "expirationDateDefined" , bool expirationDateDefined         )
      ]
      |> List.append expirationDate
      |> List.append acl
    )

encodeAcl : AccessControl -> Value
encodeAcl acl =
  object (
    [ ( "path" , string acl.path )
    , ( "verb" , string acl.verb )
    ]
  )

encodeTokenAcl : String -> List AccessControl -> Value
encodeTokenAcl tokenId acl =
  object (
    [ ( "id"  , string tokenId    )
    , ( "acl" , list encodeAcl acl )
    ]
  )