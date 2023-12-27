var scoreDetailsDispatcher = {} ;

var appNode, appNotif, createSuccessNotification, createErrorNotification, createInfoNotification;

$(document).ready(function(){
  const flags = { contextPath: contextPath};
  // --- NOTIFICATIONS ---
  appNode  = document.querySelector("rudder-notifications");
  appNotif = Elm.Notifications.init({
    node  : appNode,
    flags : flags
  });
  createSuccessNotification = function (msg){
    var message = msg ? msg : "Your changes have been saved";
    appNotif.ports.successNotification.send(message);
  };
  createLinkSuccessNotification = function (json){
    appNotif.ports.linkSuccessNotification.send(json);
  };
  createErrorNotification   = function (msg, code){
    appNotif.ports.errorNotification.send(msg, code);
  };
  createWarningNotification = function (msg, code){
    appNotif.ports.warningNotification.send(msg, code);
  };
  createInfoNotification = function (msg, code){
    appNotif.ports.infoNotification.send(msg, code);
  };
  appNode  = document.querySelector("rudder-quicksearch");
  appQuicksearch = Elm.QuickSearch.init({
    node  : appNode,
    flags : flags
  });
});