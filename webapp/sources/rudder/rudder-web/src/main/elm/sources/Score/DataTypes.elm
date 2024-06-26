module Score.DataTypes exposing (..)

import Dict exposing (Dict)
import Html exposing (Html)
import Http exposing (Error)

import Json.Decode exposing (Value)
import NodeCompliance.DataTypes exposing (NodeId)

--
-- All our data types
--

type ScoreValue = A | B | C | D | E | F | X

type alias ScoreInfo =
  { id : String
  , name : String
  }

type alias GlobalScore =
  { value   : ScoreValue -- "A/B/C/D/E/F/-"
  , message : String -- "un message en markdown"
  , details : List Score
  }

type alias Score =
  { value   : ScoreValue -- "A/B/C/D/E/F/-"
  , scoreId : String -- "compliance"
  , message : String -- "un message en markdown"
  }

type alias DetailedScore =
  { value   : ScoreValue -- "A/B/C/D/E/F/-"
  , scoreId : String -- "compliance"
  , message : String -- "un message en markdown"
  , details : Value
  }

type alias SystemUpdatesDetails =
  { update      : Maybe Int
  , enhancement : Maybe Int
  , security    : Maybe Int
  , bugfix      : Maybe Int
  }

type alias Model =
  { nodeId      : NodeId
  , score       : Maybe GlobalScore
  , contextPath : String
  , scoreInfo   : List ScoreInfo
  , scoreToShow : Maybe String
  }

type Msg = GetScore (Result Error GlobalScore) | GetScoreInfo (Result Error (List ScoreInfo)) | ShowScoreMessage (Maybe String)
