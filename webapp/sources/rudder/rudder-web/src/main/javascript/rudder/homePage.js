/*
*************************************************************************************
* Copyright 2025 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/
var g_osNames;

const homePage = (
    globalCompliance
  , globalGauge
  , nodeCompliance
  , nodeCount
  , scoreDetails
) => {
  var opts = {
    lines: 12, // The number of lines to draw
    angle: 0, // The length of each line
    lineWidth: 0.44, // The line thickness
    pointer: {
      length: 0.9, // The radius of the inner circle
      strokeWidth: 0.035, // The rotation offset
      color: '#36474E' // Fill color
    },
    limitMax         : 'false'  , // If true, the pointer will not go past the end of the gauge
    colorStart       : '#6FADCF', // Colors
    colorStop        : '#8FC0DA', // just experiment with them
    strokeColor      : '#d8dde5', // to see which ones work best for you
    percentColors    : [[0.0, "#DA291C" ], [0.30, "#EF9600"], [0.50, "#b1eda4"], [1.0, "#13BEB7"]],
    generateGradient : true
  };
  var target = document.getElementById('complianceGauge'); // your canvas element
  var gauge = new Gauge(target).setOptions(opts); // create sexy gauge!
  gauge.maxValue = 100; // set max gauge value
  gauge.animationSpeed = 14; // set animation speed (32 is default value)

  if(globalGauge < 0) { //put placeholder texte
    $("#globalCompliance").html('<div class="placeholder-bar progress m-0 mb-3"></div>');
    $("#globalComplianceStats").html(
      "<p>You only have system rules. They are ignored in global compliance.</p>"+
      "<p>Please go to <a href='/rudder/secure/configurationManager/ruleManagement'>rule management</a> to define your rules.</p>"
    )

    var complianceGauge = document.getElementById('complianceGauge');
    complianceGauge.getContext('2d').clearRect(0, 0, complianceGauge.width, complianceGauge.height);
    $("#gauge-value").text("") // let blank
    $(target).attr("title","You only have system rules. They are ignored in global compliance.")
    //Display empty gauge
    gauge.set(0)
  } else {

    $("#globalCompliance").append(buildComplianceBar(globalCompliance));

    var allNodes = nodeCount.active;
    var activeNodes ="<span class='highlight'>" + nodeCount.active + "</span> nodes."
    if (nodeCount.active == 1) {
      activeNodes = "<span class='highlight'>" + nodeCount.active + "</span> node."
    }
    var stats = "Compliance based on "+ activeNodes
    if (nodeCount.pending !== null) {
      allNodes += nodeCount.pending.nodes;
      var pendingNodes = nodeCount.pending.nodes + " nodes"
      var verb = "are"
      if (nodeCount.pending.nodes == 1) {
        pendingNodes = nodeCount.pending.nodes + " node"
        verb = "is"
      }
      stats += "<br>There "+ verb +" also " + pendingNodes + " for which we are still waiting for data (" + nodeCount.pending.percent + "%)."
    }

    $("#globalComplianceStats").html(stats);

    gauge.set(function() {
      // set actual value - there is a bug for value = 0, so let's pretend it's 0.1
      if(globalGauge == 0) return 0.1;
      return globalGauge;
    }());
    $("#gauge-value").text(globalGauge+"%");

  }

  doughnutChart('nodeCompliance', nodeCompliance, nodeCompliance.colors, nodeCompliance.colors.map(x => complianceHoverColors[x]));

  scoreDetails.forEach(function(score) {
    $("#scoreBreakdown .node-charts").append(
              `<div class="node-chart">
                <h4 class="text-center">${score.name}</h4>
                <canvas id="score-${score.scoreId}" > </canvas>
                <div  id="score-${score.scoreId}-legend"></div>
              </div>`)
    var complianceHColors = score.data.colors.map(x => complianceHoverColors[x]);
    var scoreChart = doughnutChart('score-'+ score.scoreId, score.data, score.data.colors, complianceHColors);
    var noScoreIndex = score.data.labels.indexOf("No score")
    // Hide no score details, chart is a promise execute only when fulfilled
    if (noScoreIndex !== -1) {
      scoreChart.then(function (sc){
        sc.toggleDataVisibility(noScoreIndex)
        sc.update()
      })
    }
  })

  initBsTooltips();
}

const onClickDoughnuts = (e, active, currentChart, id, data) => {
  if (active[0] !== undefined){
    // we have specific mapping of query filters for scores
    data = (data.labelQueryFilters ?? currentChart.data.labels)[active[0].index]
    const jsonHashSearch =
      { query : {select:"nodeAndPolicyServer",composition:"And"}
      , tab   : "#node_search"
      };
    const nodeListTab = "#node_list"

    switch (id) {
      case 'nodeOs':
        if (g_osNames == undefined) return ;
        const osName = g_osNames[data];
        jsonHashSearch.query.where = [
          { objectType: "node"
          , attribute : "osName"
          , comparator: "eq"
          , value     : osName
          }
        ];
        break;

      case 'nodeAgents':
        jsonHashSearch.query.where = [
          { objectType: "node"
          , attribute : "agentVersion"
          , comparator: "regex"
          , value     : "(\\d+:)?" + data.replace(/\./g, "(\.|~)") + ".*"
          }
        ];
        break;

      case 'nodeMachine':
        jsonHashSearch.query.where = [
          { objectType: "machine"
          , attribute : "machineType"
          , comparator: "eq"
          , value     : data
          }
        ];
        break;

      case 'nodeCompliance':
        const jsonHashFilter =
          { score : data
          , tab: nodeListTab
          };
        window.location = contextPath + "/secure/nodeManager/nodes#" + JSON.stringify(jsonHashFilter);
        return;

      default:
        if (id.startsWith("score-")) {
          const scoreId = id.substring(6)
          const jsonHashFilter =
            { scoreDetails : {[scoreId]:data}
            , tab: nodeListTab
            };
          window.location = contextPath + "/secure/nodeManager/nodes#" + JSON.stringify(jsonHashFilter);
          return;
        } else return;
      }

      window.location = contextPath + "/secure/nodeManager/nodes#" +  JSON.stringify(jsonHashSearch);;
    }
}

const homePageInventory = (
    nodeMachines
  , nodeOses
  , osNames
) => {
  g_osNames = osNames
  doughnutChart('nodeMachine',nodeMachines, inventoryColors, hoverColors, onClickDoughnuts);
  doughnutChart('nodeOs', nodeOses, inventoryColors, hoverColors, onClickDoughnuts);
}

const homePageSoftware = (
    nodeAgents
  , count
) => {
  doughnutChart('nodeAgents', nodeAgents, inventoryColors, hoverColors, onClickDoughnuts);
}
