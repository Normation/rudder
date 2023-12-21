module Node.DataTypes exposing (..)

import Http exposing (Error)

import NodeCompliance.DataTypes exposing (NodeId)

import ComplianceScore.DataTypes exposing (ScoreDetails, ItemType)

--
-- All our data types
--

type alias Model =
  { nodeId       : NodeId
  , scoreDetails : Maybe ScoreDetails
  , contextPath  : String
  }

type Msg = GetScoreDetails (Result Error ScoreDetails)