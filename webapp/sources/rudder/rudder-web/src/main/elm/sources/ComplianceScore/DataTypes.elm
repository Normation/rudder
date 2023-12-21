module ComplianceScore.DataTypes exposing (..)

import Http exposing (Error)

import NodeCompliance.DataTypes exposing (NodeId)
import Rules.DataTypes exposing (RuleId)
import Compliance.DataTypes exposing (ComplianceDetails)

--
-- All our data types
--

type ScoreValue = A | B | C | D | E | F | X

type alias GlobalComplianceScore =
  { value   : ScoreValue -- "A/B/C/D/E/F/-"
  , message : String -- "un message en markdown"
  , details : List GlobalScoreDetails
  }

type alias GlobalScoreDetails =
  { value   : ScoreValue -- "A/B/C/D/E/F/-"
  , name    : String -- "compliance"
  , message : String -- "un message en markdown"
  }

type alias ComplianceScoreDetails =
  { value   : ScoreValue -- "A/B/C/D/E/F/-"
  , name    : String -- "compliance"
  , message : String -- "un message en markdown"
  , details : ComplianceDetails
  }

type alias SystemUpdatesScoreDetails =
  { value   : ScoreValue -- "A/B/C/D/E/F/-"
  , name    : String -- "system-updates"
  , message : String -- "un message en markdown"
  , details : SystemUpdatesDetails -- { "updates" : x, "security" : y, ....} // bref ce qui te permet de construire un affichage des updates sur une machine }
  }

type alias SystemUpdatesDetails =
  { update      : Maybe Int
  , enhancement : Maybe Int
  , security    : Maybe Int
  , bugfix      : Maybe Int
  }

type alias ScoreDetails =
  { compliance    : ComplianceScoreDetails
  , systemUpdates : Maybe SystemUpdatesScoreDetails
  }

type ItemType
  = Node NodeId
  | Rule RuleId

type alias Model =
  { item            : Maybe ItemType
  , complianceScore : Maybe GlobalComplianceScore
  , contextPath     : String
  }

type Msg = GetComplianceScore (Result Error GlobalComplianceScore )
