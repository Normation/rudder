#!/bin/sh


# Specify path variable
PATH=/sbin:/usr/sbin:/bin:/usr/bin

SENTINEL_FILE="/var/rudder/share/root/stopFile"


# Kill me on all errors
set -e

activate_red_button_cfe() {
	/bin/touch ${SENTINEL_FILE}

	# Cfengine Community
	if test -x /var/rudder/cfengine-community/bin/cf-agent; then
		/var/rudder/cfengine-community/bin/cf-agent -Ddanger -K
	fi

	# Cfengine Nova
	if test -x /var/cfengine/bin/cf-agent; then
		/var/cfengine/bin/cf-agent -Ddanger -K
	fi
}

release_red_button_cfe() {
	/bin/rm -f ${SENTINEL_FILE}

	# Cfengine Community
	if test -x /var/rudder/cfengine-community/bin/cf-agent; then
		/var/rudder/cfengine-community/bin/cf-agent -Dsafe -K
	fi

	# Cfengine Nova
	if test -x /var/cfengine/bin/cf-agent; then
		/var/cfengine/bin/cf-agent -Dsafe -K
	fi
}

# Tell the user that something went wrong and give some hints for
# resolving the problem.
report_failure() {
	if [ -n "$reason" ]; then
		echo " - failed: "
		echo "$reason"
	else
		echo " - failed."
		cat <<EOF
The operation failed but no output was produced. For hints on what went
wrong please refer to the system's logfiles (e.g. /var/log/syslog)
EOF
	fi
}

# Activate the "red button" for cfe orchestrator and capture the error 
# message if any to $reason.
activate_cfeo_rb() {
	reason="`activate_red_button_cfe 2>&1`"
}

# Release the "red button" for cfe orchestrator and capture the error 
# message (if any) to $reason.
release_cfeo_rb() {
	reason="`release_red_button_cfe 2>&1`"
}

#check for the button already activated
check_for_no_activate() {
	if [ -e $SENTINEL_FILE ]; then 
		echo "Not activating the red button because $SENTINEL_FILE present"
		exit 0
	fi
}

#check for the button already released
check_for_no_release() {
	if [ ! -e $SENTINEL_FILE ]; then 
		echo "Not releasing the red button because $SENTINEL_FILE not present"
		exit 0
	fi
}

# Status of the Cfengine orchestrator
# 0 means that the Red Button in off
# 1 means that the Red Button is activated
status_button() {
	if [ -e $SENTINEL_FILE ]; then
		echo "Red Button is activated"
		exit 1
	else
		echo "Red Button is released"
		exit 0
	fi
}

# Start the Cfengine orchestrator
release_button() {
	echo -n "Starting Cfengine Orchestrator:"
	trap 'report_failure' 0
	release_cfeo_rb
	trap "-" 0
	echo .
}

# Stop the Cfengine orchestrator daemons
activate_button() {
	echo -n "Stopping Cfengine Orchestrator:"
	trap 'report_failure' 0
	activate_cfeo_rb
	trap "-" 0
	echo .
}

case "$1" in
  start)
	check_for_no_release
  	release_button ;;
  stop)
	check_for_no_activate
  	activate_button ;;
  status)
	status_button ;;
  *)
  	echo "Usage: $0 {start|stop|status}"
	exit 1
	;;
esac
