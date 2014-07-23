Title: ncf-builder


## Install ncf Builder

ncf builder allow you to write techniques easily using a modern web interface.

To setup it you need to install ncf as described in ncf quickstart.
Then install ncf builder with its dependencies:

	# apt-get install ncf-api-virtualenv apache2 libapache2-mod-wsgi python

If you are on apache 2.4 (debian sid or ubuntu 14.04) move /etc/apache2/conf.d/ncf-api-virtualenv.conf to /etc/apache2/conf-available and run 

	# a2enconf ncf-api-virtualenv

Change ownership of your tree to allow ncf-api to edit it:

	# adduser ncf-api-venv ncf-api-venv
	# chgrp -R ncf-api-venv /var/cfengine/inputs/
	# chmod -R g+w /var/cfengine/inputs/

Then restart apache (here on debian, use httpd on rpm based system):

	# service apache2 restart

### Patch ncf-api

The current version (0.201407150059) needs to be patched to remove built-in authentication and to change default path. 
This won't be necessary in future versions.

Edit /usr/share/ncf-api-virtualenv/ncf_api_flask_app.wsgi to remove built-in authentication:
	Line 41     : available_modules_name = ["Rudder"] 
	Replace with: available_modules_name = []

Edit /usr/share/ncf-api-virtualenv/ncf_api_flask_app.wsgi to change default path, this change must be done on all 4 path checks.
Be careful, the test content change, do not paste as is: 
	Change: if "path" in request.args:
	To    : if "path" in request.args and request.args['path'] != "":

And replace the default path to your CFEngine policies path, this is done in the else section:
	Change: path = ""
	To    : path = "/var/cfengine/inputs/" # your test path

Repeat on line 64, 78, 97, 114 and restart apache.

### Test ncf Builder

Now simply go to http://localhost/ncf-builder/ and add or modify your techniques.

Please be aware that for now this editor only edit *techniques*, you still need to write or modify a *service* file to call them from ncf.

Enjoy !
