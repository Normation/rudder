# ncf API

A Flask based REST API to manage Techniques in a ncf directory

## API functions

* [GET] /api/generics_methods: This gets all defined generics methods from parameter path and /usr/share/ncf
  * Parameters:
    * path [Optional][URL parameter]: path where to look for generic_methods

* [GET] /api/techniques?path=/ncf/folder/path: This gets all defined techniques from parameter path and /usr/share/ncf
  * Parameters:
    * path [Optional][URL parameter]: path where to look for Techniques

## Deploy ncf API

### Use ncf-api-virtualenv package (Before 5.1)

The easiest way to deploy ncf API is to install the package ncf-api-virtualenv. The package is available in the same repository than ncf.

This will:

* Create a virtual environment with all requirements
* Add a wsgi file ready to deploy ncf api using the virtual environment
* Add a ready to use apache configuration using the wsgi file

### Use your own installation

You can install by yourself requirements (directly on your system or in a specific virtual env)

All python package needed are in file requirements.txt 

You will also need a wsgi file to deploy your application. You can start from ncf-api-virtualenv wsgi (https://github.com/Normation/rudder-packages/blob/master/ncf-api-virtualenv/SOURCES/ncf_api_flask_app.wsgi) by just replacing the virtualenv path by your actual environement


## Development

### ncf API
To run ncf api in a development environment, run the following commands:

```shell
wget https://raw.githubusercontent.com/Normation/rudder-packages/master/ncf-api-virtualenv/SOURCES/virtualenv.py
python virtualenv.py flask
flask/bin/pip install -r requirements.txt
export PYTHONPATH=/path/to/ncf/tools:$PYTHONPATH
./run.py
```

ncf API is now available on http://localhost:5000 

### ncf technique editor

You will need apache to be installed (apache2/httpd)

```shell
cp  /path/to/ncf/api/dev-env/ncf-builder.conf /etc/(apache2|httpd)/conf.d/
chmod 755 /path/to/ncf/builder -R
service (apache2|httpd) restart
```

ncf API is now available on http://localhost/ncf
ncf technique editor is now available on http://localhost/ncf-builder

### Disable Rudder authentication

At this point you need a running rudder (dev or not) on localhost to access ncf api

to remove this need:

* Edit /path/to/ncf/api/ncf_flask_app/views.py
* Set value of 'use_rudder_auth' to False

