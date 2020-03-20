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
wget https://repository.rudder.io/build-dependencies/virtualenv/virtualenv-16.5.0.tar.gz
tar xzvf virtualenv-16.5.0.tar.gz
rm -rf virtualenv-16.5.0.tar.gz
mv virtualenv-16.5.0/* <ncf_path>/api/
cd <ncf_path>/api/
python virtualenv.py flask
flask/bin/pip install -r requirements.txt
export PYTHONPATH=<ncf_full_path>/tools:$PYTHONPATH
./run.py
```

ncf API is now available at http://localhost:5000 

### ncf technique editor

#### Manual install

##### CFEngine required
The simplest way to have cfengine installed and setup to the right version is to install a `rudder-agent`
To install a rudder agent:
```
wget --quiet -O- "https://repository.rudder.io/apt/rudder_apt_key.pub" | sudo apt-key add -
echo "deb http://repository.rudder.io/apt/6.0/ $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/rudder.list
sudo apt update
sudo apt install -y rudder-agent
```

##### apache required
Depending on your OS, the package will be named `apache2` (Debian-like, `apt install apache2`) or `httpd` (RHEL for example).
Be careful as directories differ a bit: `/etc/apache2/conf-enabled/` for systems using `apache2`, `/etc/httpd/conf.d/` for systems using `httpd`

> Replace `<user>` and `<ncf_full_path>`

```shell
sudo cp <ncf_full_path>/api/dev_env/ncf-builder.conf /etc/apache2/conf-enabled/
sudo sed -i "s/8042/8080/g" /etc/apache2/conf-enabled/ncf-builder.conf
sudo sed -i "s/\/path\/to\/ncf\/builder/\/home\/<user>\/rudder\/ncf\/builder/g" /etc/apache2/conf-enabled/ncf-builder.conf
chmod 755 <ncf_full_path>/builder -R
sudo a2enmod rewrite
sudo a2enmod proxy
sudo a2enmod proxy_http
sudo service apache2 restart
```

ncf API is now available on http://localhost/ncf
ncf technique editor is now available on http://localhost/ncf-builder

#### Setup script

All the previous steps are summed up in the following script: [setup_dev_env.sh](https://github.com/Normation/ncf/blob/master/api/setup_dev_env.sh)
Do not run this script as sudo! Though some commands require sudo privileges, you may be prompted to type sudo password

The first param is your local user 
The second param is the action you want to do: simply `--install`, `--run` or do both (`--full`)
This script should be copied anywhere on your machine and simply executed.
```bash
./setup_dev_env.sh <user> <--action>
```
Then let it run (the running script is the ncf api)

### Disable Rudder authentication

At this point you need a running rudder (dev or not) on localhost to access ncf api

to remove this need:

* Edit /path/to/ncf/api/ncf_flask_app/views.py
* Set value of 'use_rudder_auth' to False

