/*
*************************************************************************************
* Copyright 2014 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

function homePage (
    globalCompliance
  , globalGauge
  , nodeCompliance
  , nodeComplianceColors
) {
  $("#globalCompliance").append(buildComplianceBar(globalCompliance));
  createTooltip();

  
  var opts = {
      lines: 12, // The number of lines to draw
      angle: 0, // The length of each line
      lineWidth: 0.44, // The line thickness
      pointer: {
        length: 0.9, // The radius of the inner circle
        strokeWidth: 0.035, // The rotation offset
        color: '#000000' // Fill color
      },
      limitMax: 'false',   // If true, the pointer will not go past the end of the gauge
      colorStart: '#6FADCF',   // Colors
      colorStop: '#8FC0DA',    // just experiment with them
      strokeColor: '#E0E0E0',   // to see which ones work best for you
      percentColors : [[0.0, "#d9534f" ], [0.30, "#f0ad4e"], [0.50, "#5bc0de"], [1.0, "#5cb85c"]],
      generateGradient: true
    };
    var target = document.getElementById('complianceGauge'); // your canvas element
    var gauge = new Gauge(target).setOptions(opts); // create sexy gauge!
    gauge.maxValue = 100; // set max gauge value
    gauge.animationSpeed = 25; // set animation speed (32 is default value)
    gauge.set(globalGauge); // set actual value
    $("#gauge-value").text(globalGauge+"%");

  var height = $(window).height() / 2 ;
  
  c3.generate( {
      size: { height: height }
    , bindto: '#nodeCompliance'
    , data: {
          columns: nodeCompliance
        , type : 'donut'
        , order : null
        , colors : nodeComplianceColors
        , color: function (color, d) { return color }
      }
    , donut : {
        label: {
          format: function (v, ratio) {return (ratio * 100).toFixed(0) + '%'; }
        }
      }
  } );
}

function homePageInventory (
    nodeMachines
  , nodeOses
) {
  var smallHeight =  $(window).height() / 4 ;

  c3.generate({
      size: { height: smallHeight }
    , bindto: '#nodeMachine'
    , data: {
          columns: nodeMachines
        , type : 'donut'
      }
    , donut : {
        label: {
          show: false
        }
      }
  } );
    
  c3.generate({
    size: { height: smallHeight }
  , bindto: '#nodeOs'
  , data: {
        columns: nodeOses
      , type : 'donut'
    }
  , donut : {
      label: {
        show: false
      }
    }
  } );      
}

function homePageSoftware (
      nodeAgents
  ) {
  var smallHeight =  $(window).height() / 4 ;
 
  c3.generate({
    size: { height: smallHeight }
  , bindto: '#nodeAgents'
  , data: {
        columns: nodeAgents
      , type   : 'donut'
    }
  , donut : {
      label: {
        show: false
      }
    }
  } );
}