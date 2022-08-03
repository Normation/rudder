module AgentValueParser exposing (..)

import Parser exposing (..)
import DataTypes exposing (..)
import Regex
import Set

displayValue : List AgentValue -> String
displayValue value =
  String.join "" (List.map displayValueHelper value)

displayValueHelper : AgentValue -> String
displayValueHelper value =
  case value of
    Value s -> s
    Variable v -> "${" ++ (displayValue v) ++ "}"

canonify : List AgentValue -> String
canonify value =
  String.join "" (List.map canonifyHelper value)

canonifyHelper : AgentValue -> String
canonifyHelper value =
  case value of
    Value s -> Regex.replace ((Regex.fromString >> Maybe.withDefault Regex.never) "[^_a-zA-Z\\d]") (always "_") s
    Variable v -> "${" ++ (displayValue v) ++ "}"

isEmptyValue: List AgentValue -> Bool
isEmptyValue v =
  List.isEmpty v || List.all isEmptyValueHelper v

isEmptyValueHelper : AgentValue -> Bool
isEmptyValueHelper v =
  case v of
    Value "" -> True
    Value _ -> False
    Variable _ -> False

lengthValue : List AgentValue -> Int
lengthValue v =
  List.sum (List.map lengthValueHelper v)

lengthValueHelper : AgentValue -> Int
lengthValueHelper v =
  case v of
    Value s -> String.length s
    Variable s -> 3 + (lengthValue s)

parseValue: Parser AgentValue
parseValue =
  succeed Value
    |= variable  { start = (/=) '$', inner = (/=) '$', reserved = Set.empty}

parseDollar =
  succeed (Value "$")
    |. symbol "$"

parseDollarValue: Parser AgentValue
parseDollarValue =
  succeed (\v -> Value ("$"++v))
    |. symbol "$"
    |= variable  { start = (/=) '{', inner = (/=) '$', reserved = Set.empty}

parseInnerValue: Parser AgentValue
parseInnerValue =
  succeed Value
    |= variable  { start = \c -> c /= '}' && c /= '$'&& c /= ' '&& c /= '\t'&& c /= '\n', inner = \c -> c /= '}' && c /= '$' && c /= ' '&& c /= '\t'&& c /= '\n', reserved = Set.empty }

parseInnerDollarValue: Parser AgentValue
parseInnerDollarValue =
  succeed (\v-> Value ("$"++v))
    |. symbol "$"
    |= variable  { start = \c -> c /= '}' && c /= '{' && c /= ' '&& c /= '\t'&& c /= '\n' , inner = \c -> c /= '}' && c /= '$' && c /= ' '&& c /= '\t'&& c /= '\n', reserved = Set.empty }


parseVariable : Parser AgentValue
parseVariable =
    oneOf [
      succeed Variable
        |. Parser.symbol "${"
        |= innerLoop
        |. Parser.symbol "}"
    ]

parseAgentValue : Parser AgentValue
parseAgentValue =
  oneOf [
    backtrackable parseVariable
  , parseValue
  , succeed (\v -> Value ("${" ++ (displayValue [v])))
        |. Parser.symbol "${"
        |= parseValue
  , backtrackable parseDollarValue
  , parseDollar
  ]


innerLoop : Parser (List AgentValue)
innerLoop =
      loop [] innerLoopHelp

innerLoopHelp : List AgentValue -> Parser (Step (List AgentValue) (List AgentValue))
innerLoopHelp revStmts =
      oneOf
        [
          succeed (\stmt -> Loop (stmt :: revStmts))
            |= oneOf [
                            lazy (\_ -> parseVariable)
                          , parseInnerValue
                          , parseInnerDollarValue
                          , parseDollar
                          ]
        ,  succeed () |> map (\_ -> Done  (List.reverse revStmts))
        ]


valueLoop : Parser (List AgentValue)
valueLoop =
      loop [] valueLoopHelper

valueLoopHelper : List AgentValue -> Parser (Step (List AgentValue) (List AgentValue))
valueLoopHelper revStmts =
      oneOf
        [ end  |> map (\_ -> Done (List.reverse revStmts))
        , succeed (\stmt -> Loop (stmt :: revStmts))
            |= parseAgentValue
        ]

getAgentValue: String -> List AgentValue
getAgentValue s =
  case run valueLoop s of
    Err _ -> [Value s]
    Ok v ->  v

