# Cron file for Rudder
#
# Will manually run cf-agent in case cf-execd is no longer running. cf-agent will fire up a new cf-execd.
#
# To temporarily avoid this behaviour, run 'rudder agent disable'
# Don't forget to remove that file when you're done!

0,5,10,15,20,25,30,35,40,45,50,55 * * * * root /opt/rudder/bin/rudder agent check >/dev/null
