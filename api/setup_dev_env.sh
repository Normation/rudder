#!/bin/bash

# Do NOT run this script as root
# Disclaimer: the --install part has been written and tested on and for a Debian10 and may not work on other distributions
# Note that this script has been designed to work as a full and standalone installer, every single package it needs is installed from within it

username=$1
action=$2
rudder_dir=/home/$username/rudder

if [[ $action == "--install" || $action == "--full" ]];
then

######################################################
# PACKAGE DEPENDENCIES INSTALLATION + HOME PRE-SETUP #
######################################################

    sudo chown $username -R /home/$username/
    mkdir -p $rudder_dir
    cd $rudder_dir

    sudo /usr/sbin/usermod -aG sudo $username
    sudo apt update
    sudo apt install -y python3 python3-pip apache2
    pip3 install requests urllib3 flask

####################################
# NCF API & TECHNIQUE EDITOR SETUP #
####################################

    git clone https://github.com/Normation/ncf.git

    # local rudder agent required only if the technique editor is part of the test
    wget --quiet -O- "https://repository.rudder.io/apt/rudder_apt_key.pub" | sudo apt-key add -
    echo "deb http://repository.rudder.io/apt/6.0/ $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/rudder.list
    sudo apt update
    sudo apt install -y rudder-agent

    # setup ncf api
    wget https://repository.rudder.io/build-dependencies/virtualenv/virtualenv-16.5.0.tar.gz
    tar xzvf virtualenv-16.5.0.tar.gz
    rm -rf virtualenv-16.5.0.tar.gz
    mv virtualenv-16.5.0/* $rudder_dir/ncf/api/
    cd $rudder_dir/ncf/api/
    python virtualenv.py flask
    flask/bin/pip install -r requirements.txt
    # setup technique editor
    # depending on your distrib, folder might be: `/etc/apache2/conf.d/`
    sudo cp $rudder_dir/ncf/api/dev_env/ncf-builder.conf /etc/apache2/conf-enabled/
    sudo sed -i "s/8042/8080/g" /etc/apache2/conf-enabled/ncf-builder.conf
    sudo sed -i "s/\/path\/to\/ncf\/builder/\/home\/$username\/rudder\/ncf\/builder/g" /etc/apache2/conf-enabled/ncf-builder.conf
    chmod 755 $rudder_dir/ncf/builder -R
    sudo a2enmod rewrite
    sudo a2enmod proxy
    sudo a2enmod proxy_http
    sudo service apache2 restart
fi

if [[ $action == "--run" || $action == "--full" ]];
then
#######################
# RUN PART, NCF API * #
#######################
    export PYTHONPATH=$rudder_dir/ncf/tools:$PYTHONPATH
    $rudder_dir/ncf/api/run.py
fi
