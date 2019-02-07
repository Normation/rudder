# Cron file for Rudder
#
# Will manually run cf-agent in case cf-execd is no longer running. cf-agent will fire up a new cf-execd.
#
# To temporarily avoid this behaviour, touch /opt/rudder/etc/disable-agent.
# Don't forget to remove that file when you're done!
#
# If you want to report a specific message if the Rudder agent fails to restart, please create the
# /opt/rudder/etc/rudder-restart-message.txt file with your custom message inside. It will be sent by mail
# instead of the default one.

0,5,10,15,20,25,30,35,40,45,50,55 * * * * root /opt/rudder/bin/check-rudder-agent >/dev/null
