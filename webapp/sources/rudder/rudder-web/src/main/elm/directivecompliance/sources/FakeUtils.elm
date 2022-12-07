module FakeUtils exposing (..)

import DataTypes exposing (..)
import ComplianceUtils exposing (..)

fakeComplianceDetails1 : ComplianceDetails
fakeComplianceDetails1 =
  ComplianceDetails Nothing (Just 100.0) Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing

fakeComplianceDetails2 : ComplianceDetails
fakeComplianceDetails2 =
  ComplianceDetails Nothing (Just 50.0) Nothing Nothing Nothing Nothing Nothing Nothing (Just 50.0) Nothing Nothing Nothing Nothing Nothing

fakeComplianceDetails3 : ComplianceDetails
fakeComplianceDetails3 =
  ComplianceDetails Nothing Nothing Nothing Nothing Nothing Nothing (Just 25.0) Nothing Nothing Nothing Nothing Nothing Nothing (Just 25.0)


fakeRuleCompliance : List (RuleCompliance a)
fakeRuleCompliance =
  [ RuleCompliance (RuleId "10ef6343-2a73-4f41-bcd0-ad734aab687a") "Rule #1A" 100.0 fakeComplianceDetails1 []
  , RuleCompliance (RuleId "32377fd7-02fd-43d0-aab7-28460a91347b") "Global configuration for all nodes" 100.0 fakeComplianceDetails1 []
  , RuleCompliance (RuleId "413ed504-156b-4f15-81c0-35c71f265a0c") "Rule #2" 50.0  fakeComplianceDetails2 []
  , RuleCompliance (RuleId "6f3c8abc-d629-46d8-b337-d10c2fd07aff") "Rule #11A" 100.0 fakeComplianceDetails1 []
  , RuleCompliance (RuleId "7f0907fc-5e3f-4738-b5a3-8cd663f981b1") "Rule #1" 0     fakeComplianceDetails3 []
  , RuleCompliance (RuleId "843d2670-abff-45ef-9494-47eac099f342") "Rule #11B" 50.0  fakeComplianceDetails2 []
  , RuleCompliance (RuleId "85ed6461-ec20-48b0-8ac5-4713c31befac") "Rule test #11AB" 100.0 fakeComplianceDetails1 []
  , RuleCompliance (RuleId "c9103b24-1f47-4add-b567-61c52da34676") "Clone of Rule #1" 100.0 fakeComplianceDetails1 []
  ]

fakeNodeCompliance : List NodeCompliance
fakeNodeCompliance =
  [ NodeCompliance (NodeId "root"                                ) "server.rudder.local" 100.0 fakeComplianceDetails1 fakeRuleCompliance
  , NodeCompliance (NodeId "08bd9be3-7bca-40f3-b6e9-b9b962c4da3c") "agent8.rudder.local" 100.0 fakeComplianceDetails2 fakeRuleCompliance
  , NodeCompliance (NodeId "6069b351-9db4-4648-9559-85fd1f8ccee6") "agent9.rudder.local" 50.0  fakeComplianceDetails3 fakeRuleCompliance
  ]

fakeDirectiveCompliance : Maybe DirectiveCompliance
fakeDirectiveCompliance =
  Just (DirectiveCompliance 100.0 fakeComplianceDetails1 fakeRuleCompliance fakeNodeCompliance)