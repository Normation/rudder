#####################################################################################
# Copyright 2017 Normation SAS
#####################################################################################

.DEFAULT_GOAL := all

# all
all: rudder-plugin-auth-radius rudder-plugin-aix

rudder-plugin-auth-radius:
	cd rudder-plugin-auth-radius
	make

rudder-plugin-aix:
	cd rudder-plugin-aix
	make


