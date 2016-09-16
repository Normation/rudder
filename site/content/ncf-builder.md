Title: ncf-builder


## Install ncf Builder

ncf builder allow you to write techniques easily using a modern web interface.

To setup it you need to install ncf as described in ncf quickstart.
Then install ncf builder with its dependencies:

	# apt-get install ncf-api-virtualenv

Change ownership of your tree to allow ncf-api to edit it:

	# chgrp -R www-data /var/cfengine/inputs/
	# chmod g+x /var/cfengine/inputs/
	# chmod -R g+rw /var/cfengine/inputs/

### Patch ncf-api

The current version needs to be patched to remove built-in authentication and to change default path. 
This won't be necessary in future versions.


Edit `/usr/share/ncf/api/ncf_api_flask_app/views.py` to remove built-in authentication:
```
	Line 71     : available_modules_name = ["Rudder"] 
	Replace with: available_modules_name = ["None"]
```

Edit `/usr/share/ncf/api/ncf_api_flask_app/views.py` to change default path.

```
Replace the default path to your CFEngine policies path:
	Change: default_path = ""
	To    : default_path = "/var/cfengine/inputs/" # your test path
```

Then restart apache.

### Test ncf Builder

Now simply go to [http://localhost/ncf-builder/](http://localhost/ncf-builder/) and add or modify your techniques.

Please be aware that for now this editor only edit *techniques*, you still need to write or modify a *service* file to call them from ncf.

Enjoy !
