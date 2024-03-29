module Node.DataTypes exposing (..)

import Dict exposing (Dict)
import Html exposing (Html)
import Http exposing (Error)

import NodeCompliance.DataTypes exposing (NodeId)

import Score.DataTypes exposing (DetailedScore, ScoreInfo)

--
-- All our data types
--

type alias Model =
  { nodeId       : NodeId
  , details : List DetailedScore
  , detailsHtml : Dict String (List (Html Msg))
  , contextPath  : String
  , scoreInfo : List ScoreInfo
  }

type Msg = GetScoreDetails (Result Error (List DetailedScore))
         | ReceiveDetails String String
         | GetScoreInfo (Result Error (List ScoreInfo))
