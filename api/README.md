# ncf API

A Flask based REST API to manage Techniques in a ncf directory

## API functions

* [GET] /api/generics_methods: This gets all defined generics methods from parameter path and /usr/share/ncf
  * Parameters:
    * path [Optional][URL parameter]: path where to look for generic_methods

* [GET] /api/techniques?path=/ncf/folder/path: This gets all defined techniques from parameter path and /usr/share/ncf
  * Parameters:
    * path [Optional][URL parameter]: path where to look for Techniques

* [POST/PUT] /api/techniques: Create(POST)/Update(PUT) a technique from technique metadata into the ncf path passed as parameters.
  * Parameters:
    * path [Optional][JSON parameter]: path where the techique will be written ( default value: /usr/share/ncf )
    * technique [JSON parameter]: Technique metadata, need to write the file

* [DELETE] /api/techniques/bundle_name: Delete technique bunlde_name in specified ncf path
  * Parameters:
    * path [Optional][URL parameter]: path where the technique will be deleted (default value: /usr/share/ncf)
    * bundle_name [URL]: Bundle name of the technique, used as technique filename to delete

## Deploy ncf API

### Use ncf-api-virtualenv package

The easiest way to deploy ncf API is to install the package ncf-api-virtualenv. The package is available in the same repository than ncf.

This will:

* Create a virtual environment with all requirements
* Add a wsgi file ready to deploy ncp api using the virtual environment
* Add a ready to use apache configuration using the wsgi file

### Use your own installation

You can install by yourself requirements (directly on your system or in a specific virtual env)

All python package needed are in file requirements.txt 

You will also need a wsgi file to deploy your application. You can start from ncf-api-virtualenv wsgi (https://github.com/Normation/rudder-packages/blob/master/ncf-api-virtualenv/SOURCES/ncf_api_flask_app.wsgi) by just replacing the virtualenv path by your actual environement


## Development

To run ncf api in a development environment, run the following commands:

```shell
wget https://raw.githubusercontent.com/Normation/rudder-packages/master/ncf-api-virtualenv/SOURCES/virtualenv.py
python virtualenv.py flask
flask/bin/pip install -r requirements.txt
export PYTHONPATH=/path/to/ncf/tools:$PYTHONPATH
./run.py
```

ncf API is now available on http://localhost:5000 
