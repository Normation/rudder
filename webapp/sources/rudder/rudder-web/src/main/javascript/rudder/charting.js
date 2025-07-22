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

const inventoryColors =
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

const hoverColors =
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

const complianceHoverColors =
  { "#5bc0de" : "#2ebee9ff"
  , "#B1BBCB" : "#9fb1cbff"
  , "#13BEB7" : "#15ccc5ff"
  , "#B1EDA4" : "#94f57fff"
  , "#EF9600" : "#ffaf27ff"
  , "#DA291C" : "#ed1809ff"
  }

const scoreColors =
  { "A" : "#13BEB7"
  , "B" : "#68C96A"
  , "C" : "#B3D337"
  , "D" : "#FEDC04"
  , "E" : "#F0940E"
  , "F" : "#DA291C"
  , "X" : "#E2E8F3"
  };

const scoreHoverColors =
  { "A" : "#15CCC5"
  , "B" : "#74E077"
  , "C" : "#BADD34"
  , "D" : "#FEDC04"
  , "E" : "#FF9700"
  , "F" : "#ED1809"
  , "X" : "#EBEEF4"
  };

const defaultClickFunction = (e, active, currentChart, id, data ) => {};

const doughnutChart = (id,data,colors,hoverColors,onClickFunction = defaultClickFunction) => {

  var context = $("#"+id)
  var count = data.values.length < 1 ? 0 : data.values.reduce((a, b) => a + b, 0);

  var borderW = data.values.length > 1 ? 3 : 0;
  var chartData = {
    labels  :  data.labels,
    datasets:
      [ { data           : data.values
        , borderWidth    : borderW
        , backgroundColor: colors
        , hoverBackgroundColor : hoverColors
      } ]
  };

  var chartOptions = {
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
      , onClick: (e, active, currentChart) => { onClickFunction(e, active, currentChart, id, data) }
      }
    , plugins: [htmlLegendPlugin],
    }
  var chart = new Chart(context, chartOptions);
  window[id] = chart;
}


// home page chart legend function

// Hide data on click
const hideChartData = (event, index, name) => {
  // Get chart data
  var chart = event.view[name];
  var meta = chart.getDatasetMeta(0);
  // Hide selected data
  meta.data[index].hidden = !meta.data[index].hidden;
  chart.update();
};

// When hovering legend display tooltip and highlight
const openTooltip = (event ,index, name) => {
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
const closeTooltip = (e ,index, name) => {
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
