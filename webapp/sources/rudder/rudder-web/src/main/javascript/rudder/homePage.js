/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

/**
 * By convention, if the globalGauge value is <0, then we consider that the
 * compliance information should not be displayed because there is no user-defined rules.
 * In that case, we just display placehoder texts.
 */


const getOrCreateLegendList = (chart, id) => {
  const legendContainer = document.getElementById(id);
  let listContainer = legendContainer.querySelector('ul');

  if (!listContainer) {
    listContainer = document.createElement('ul');
    listContainer.classList.add("graph-legend");

    legendContainer.appendChild(listContainer);
  }

  return listContainer;
};

const htmlLegendPlugin = {
  id: 'htmlLegend',
  afterUpdate(chart, args, options) {
    const ul = getOrCreateLegendList(chart, options.containerID);

    // Remove old legend items
    while (ul.firstChild) {
      ul.firstChild.remove();
    }

    // Reuse the built-in legendItems generator
    const items = chart.options.plugins.legend.labels.generateLabels(chart);

    items.forEach(item => {
      const li = document.createElement('li');
      li.classList.add("legend");

      li.onclick = () => {
        const {type} = chart.config;
        if (type === 'pie' || type === 'doughnut') {
          // Pie and doughnut charts only have a single dataset and visibility is per item
          chart.toggleDataVisibility(item.index);
        } else {
          chart.setDatasetVisibility(item.datasetIndex, !chart.isDatasetVisible(item.datasetIndex));
        }
        chart.update();
      };

      // Color box
      const boxSpan = document.createElement('span');
      boxSpan.classList.add("legend-square")
      boxSpan.style.background = item.fillStyle;
      boxSpan.style.borderColor = item.strokeStyle;
      boxSpan.style.borderWidth = item.lineWidth + 'px';

      // Text
      const textContainer = document.createElement('p');
      textContainer.style.color = item.fontColor;
      textContainer.style.margin = 0;
      textContainer.style.padding = 0;
      textContainer.style.textDecoration = item.hidden ? 'line-through' : '';

      const text = document.createTextNode(item.text);
      textContainer.appendChild(text);

      li.appendChild(boxSpan);
      li.appendChild(textContainer);
      ul.appendChild(li);
    });
  }
};

var g_osNames

function homePage (
    globalCompliance
  , globalGauge
  , nodeCompliance
  , nodeCount
  , scoreDetails
) {
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
    $(target).attr("title","You only have system rules. They are ignored in Global compliance.")
    //Display empty gauge
    gauge.set(0)
  } else {

    $("#globalCompliance").append(buildComplianceBar(globalCompliance));

    var allNodes = nodeCount.active;
    var activeNodes ="<span class='highlight'>" + nodeCount.active + "</span> Nodes."
    if (nodeCount.active <= 1) {
      activeNodes = "<span class='highlight'>" + nodeCount.active + "</span> Node."
    }
    var stats = "Compliance based on "+ activeNodes
    if (nodeCount.pending !== null) {
      allNodes += nodeCount.pending.nodes;
      var pendingNodes = nodeCount.pending.nodes + " Nodes"
      var verb = "are"
      if (nodeCount.active <= 1) {
        pendingNodes = nodeCount.pending.nodes + " Node"
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

var inventoryColors =
  [ "#13beb7"
  , "#EF9600"
  , "#da291c"
  , "#6dd7ad"
  , "#b1eda4"
  , "#f2e27d"
  , "#f6ffa4"
  , "#8fe3a8"
  , "#e78225"
  , "#d4f7a2"
  , "#48cbb2"
  , "#eec459"
  , "#e25c1a"
  ];

var hoverColors =
  [ "#15ccc5"
  , "#ffaf27"
  , "#ed1809"
  , "#5de7b0"
  , "#82f16c"
  , "#fee856"
  , "#eeff48"
  , "#54ea7f"
  , "#ff8311"
  , "#c5fd74"
  , "#5eecd2"
  , "#ffbf19"
  , "#ed4e00"
  ];

var complianceHoverColors =
  { "#5bc0de" : "#2ebee9ff"
  , "#B1BBCB" : "#9fb1cbff"
  , "#13BEB7" : "#15ccc5ff"
  , "#B1EDA4" : "#94f57fff"
  , "#EF9600" : "#ffaf27ff"
  , "#DA291C" : "#ed1809ff"
  }

async function doughnutChart(id, data, colors, hoverColors) {

  const existingChart = Chart.getChart(id)
  if (!!existingChart) existingChart.destroy()

  await waitForElement(`#${id}`)
  const ctx = document.getElementById(id)
  const count = data.values.length < 1 ? 0 : data.values.reduce((a, b) => a + b, 0);

  const borderW = data.values.length > 1 ? 3 : 0;
  const chartData = {
    labels  :  data.labels,
    datasets:
      [ { data           : data.values
        , borderWidth    : borderW
        , backgroundColor: colors
        , hoverBackgroundColor : hoverColors
      } ]
  };

  const chartOptions = {
      type: 'doughnut'
    , data: chartData
    , options: {
        animation: {
            // in ms
            duration: 500
        },
        plugins: {
          htmlLegend: {
          // ID of the container to put the legend in
            containerID: id+"-legend",

          }
        , legend: {
            display: false,
          }
        , tooltip: {
            callbacks: {
              label : function(context) {
                return " "  + context.label + ": " + context.formattedValue + " - "+ (context.parsed/count*100).toFixed(0) + "%";
              }
            }
          }
        }


      , events: ['click', 'mousemove']
      , onClick: (e, active, currentChart) => {
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
      }
    , plugins: [htmlLegendPlugin],
    }
  const chart = new Chart(ctx, chartOptions);
  window[id] = chart;
  return chart;
}


// home page chart legend function

// Hide data on click
 function hideChartData (event, index, name) {
  // Get chart data
  var chart = event.view[name];
  var meta = chart.getDatasetMeta(0);
  // Hide selected data
  meta.data[index].hidden = !meta.data[index].hidden;
  chart.update();
};

// When hovering legend display tooltip and highlight
function openTooltip(event ,index, name){
  // Get chart
  var chart = event.view[name];

  // Check if the element is already in the 'active' elements of the chart
  if(chart.tooltip._active == undefined)
     chart.tooltip._active = []
  var activeElements = chart.tooltip._active;

  // Get our element
  var requestedElem = chart.getDatasetMeta(0).data[index];

  // If already in active elements, skip
  for(var i = 0; i < activeElements.length; i++) {
      if(requestedElem._index == activeElements[i]._index)
         return;
  }
  // Add element
  activeElements.push(requestedElem);
  chart.tooltip._active = activeElements;

  // Highlight element
  requestedElem.custom = requestedElem.custom || {};
  requestedElem.custom.backgroundColor = Chart.helpers.getHoverColor(requestedElem._view.backgroundColor)

  chart.tooltip.update(true);
  chart.update(0);
  chart.draw();
}

//When mouse out of legend remove tooltip and highlight
function closeTooltip(e ,index, name){
  // Get chart
  var chart = e.view[name];

  // Get active elements
  var activeElements = chart.tooltip._active;
  if(activeElements == undefined || activeElements.length == 0)
    // No elements, nothing to do
    return;

  // our element
  var requestedElem = chart.getDatasetMeta(0).data[index];

  // Remove our element from active ones
  for(var i = 0; i < activeElements.length; i++) {
      if(requestedElem._index == activeElements[i]._index)  {
         activeElements.splice(i, 1);
         break;
      }
  }
  chart.tooltip._active = activeElements;
  chart.tooltip.update(true);

  // Remove highlight by unsetting custom attributes
  requestedElem.custom =  {};

  chart.update(0);
  chart.draw();
}

function homePageInventory (
    nodeMachines
  , nodeOses
  , osNames
) {
  g_osNames = osNames
  doughnutChart('nodeMachine',nodeMachines, inventoryColors, hoverColors);
  doughnutChart('nodeOs', nodeOses, inventoryColors, hoverColors);
}

function homePageSoftware (
    nodeAgents
  , count
) {
  doughnutChart('nodeAgents', nodeAgents, inventoryColors, hoverColors);
}
