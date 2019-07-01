var appNode, appNotif, createSuccessNotification, createErrorNotification;

$(document).ready(function(){
  const flags = { contextPath: contextPath};
  // --- NOTIFICATIONS ---
  appNode  = document.querySelector("rudder-notifications");
  appNotif = Elm.RudderNotifications.embed(appNode, flags);
  createSuccessNotification = function (msg){
    var message = msg ? msg : "Your changes have been saved";
    appNotif.ports.successNotification.send(message);
  };
  createErrorNotification   = function (msg, code){
    appNotif.ports.errorNotification.send(msg, code);
  };
  createWarningNotification = function (msg, code){
    appNotif.ports.warningNotification.send(msg, code);
  };
})


