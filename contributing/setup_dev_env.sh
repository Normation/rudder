#!/bin/bash

# Do NOT run this script as root
# Disclaimer: the --install script has been written and tested on and for a Debian10 and may not work on other distributions
# Note that this script has been designed to work as a full and standalone installer, every single package it needs is installed from within it

# If running on a VM
# The following command should be ran on the host OS (replace vm-name by the name of your vm):
# VBoxManage modifyvm vm-name --nested-hw-virt on

set -xe
username=$1
gituser=$2
rudder_dir=/home/$username/rudder


######################################################
# PACKAGE DEPENDENCIES INSTALLATION + HOME PRE-SETUP #
######################################################

    sudo chown $username -R /home/$username/
    mkdir -p $rudder_dir
    cd $rudder_dir

    sudo /usr/sbin/usermod -aG sudo $username
    sudo apt update
    sudo apt install -y git openssh-server curl wget python3 python3-pip openjdk-11-jdk net-tools ldap-utils maven apache2
    sudo service ssh start
    pip3 install docopt requests pexpect urllib3
###################################
# REQUIRED SOFTWARES INSTALLATION #
###################################

    # install vagrant latest
    vagrantversion=$(wget -qO- https://raw.githubusercontent.com/hashicorp/vagrant/stable-website/version.txt) 
    wget -q https://releases.hashicorp.com/vagrant/${vagrantversion}/vagrant_${vagrantversion}_x86_64.deb
    sudo dpkg -i vagrant_${vagrantversion}_x86_64.deb
    sudo apt -f install
    rm -rf vagrant_${vagrantversion}_x86_64.deb

    # install virtualbox + extension pack
    sudo add-apt-repository "deb http://download.virtualbox.org/virtualbox/debian bionic contrib"
    wget -qO- https://www.virtualbox.org/download/oracle_vbox_2016.asc | sudo apt-key add -
    sudo apt update
    vboxlatest=$(wget -qO- https://download.virtualbox.org/virtualbox/LATEST.TXT)
    vboxversion=$(echo $vboxlatest | cut -c1-3)
    sudo apt install -y virtualbox-${vboxversion}
    wget -q https://download.virtualbox.org/virtualbox/${vboxlatest}/Oracle_VM_VirtualBox_Extension_Pack-${vboxlatest}.vbox-extpack
    echo y | sudo vboxmanage extpack install --replace Oracle_VM_VirtualBox_Extension_Pack-${vboxlatest}.vbox-extpack
    rm -rf Oracle_VM_VirtualBox_Extension_Pack-${vboxlatest}.vbox-extpack

    # install intelliJ
    wget -q https://download.jetbrains.com/idea/ideaIC-2019.3.3.tar.gz
    sudo tar -xzf ideaIC-2019.3.3.tar.gz -C /opt
    rm -rf ideaIC-2019.3.3.tar.gz
    # /opt/idea-IC-193.6494.35/bin/idea.sh # to open it

    #install ldap apache directory studio
    wget -q http://apache.mirrors.ovh.net/ftp.apache.org/dist/directory/studio/2.0.0.v20180908-M14/ApacheDirectoryStudio-2.0.0.v20180908-M14-linux.gtk.x86_64.tar.gz
    sudo tar -xzf ApacheDirectoryStudio-2.0.0.v20180908-M14-linux.gtk.x86_64.tar.gz -C /opt
    rm -rf ApacheDirectoryStudio-2.0.0.v20180908-M14-linux.gtk.x86_64.tar.gz
    # /opt/ApacheDirectoryStudio/ApacheDirectoryStudio # to open it

    # install Elm
    wget -qO elm.gz https://github.com/elm/compiler/releases/download/0.19.0/binary-for-linux-64-bit.gz
    gzip -d elm.gz
    chmod +x elm
    sudo mv elm /usr/local/bin/
    rm -rf elm.gz

###########################
# RUDDER ENV INSTALLATION #
###########################

    # install rudder-tests environment (required for later use)
    git clone https://github.com/$gituser/rudder
    cd $rudder_dir/rudder
    git remote add upstream https://github.com/Normation/rudder
    git pull --rebase upstream master

    cd $rudder_dir
    git clone https://github.com/Normation/rudder-techniques.git
    git clone https://github.com/Normation/rudder-tests
    git clone https://github.com/Normation/rudder-api-client
    git clone https://github.com/Normation/ncf

########################
# RTF DEV ENV VM SETUP #
########################

    sudo mkdir -p /var/rudder/
    sudo chown -R $username /var/rudder
    cd $rudder_dir/rudder-tests
    sed -i "s/env python/env python3/" $rudder_dir/rudder-tests/rtf
    ln -s ../rudder-api-client
    cd $rudder_dir/rudder-api-client/lib.python
    sh build.sh # must be executed from within its own directory
    cd $rudder_dir/rudder-tests
    echo -e '{\n  "default":{ "run-with": "vagrant", "rudder-version": "6.0", "system": "debian9", "inventory-os": "debian" },\n  "server": { "rudder-setup": "dev-server" }\n}' > platforms/debian9_dev.json
    ./rtf platform setup debian9_dev
    # will take a while...
    netstat -laputn | grep 15432 # check purpose only, should print : `tcp        0      0 0.0.0.0:15432           0.0.0.0:*               LISTEN`
    cd $rudder_dir/

###########################
# PRE SETUP FOR SOFTWARES #
###########################

    
    sudo mkdir -p /var/rudder/inventories/incoming /var/rudder/share /var/rudder/inventories/accepted-nodes-updates /var/rudder/inventories/received /var/rudder/inventories/failed /var/log/rudder/core /var/log/rudder/compliance/ /var/rudder/run/ /var/rudder/configuration-repository/ncf
    sudo touch /var/log/rudder/core/rudder-webapp.log /var/log/rudder/compliance/non-compliant-reports.log /var/rudder/run/api-token
    # since a lot has been installed as root apps launched as $username could need permissions to access its home space
    sudo chown $username -R /var/rudder
    sudo chown $username -R /var/log/rudder
    sudo chown $username -R /home/$username/

    cp -R $rudder_dir/rudder-techniques/techniques /var/rudder/configuration-repository/
    # setup maven dependencies in intellij
    mkdir -p /home/$username/.m2
    cp $rudder_dir/rudder/contributing/settings.xml /home/$username/.m2/settings.xml
    sed -i "s/\[PATH TO \.m2 DIRECTORY\]/\/home\/$username\//" /home/$username/.m2/settings.xml
    #takes a while
    cd $rudder_dir/rudder/webapp/sources && mvn clean install

    # Technique editor
    sudo ln -s $rudder_dir/ncf /usr/share/ncf
    chmod 755 $rudder_dir/ncf/builder -R
    cp $rudder_dir/rudder/contributing/rudder.conf /etc/apache2/sites-enabled/
    sed -i "s#<pathToncfRepo>#$rudder_dir/ncf#" /etc/apache2/sites-enabled/rudder.conf
    sudo service apache2 restart


###################################################
# SOFTWARES TO SETUP :::: HAVE TO DO IT MANUALLY  #
###################################################

    # https://github.com/Normation/rudder/blob/master/contributing/webapp.md#part-2--setup-workspace-development-with-intellij-and-maven to set it up
    # /opt/idea-IC-193.6494.35/bin/idea.sh
    
    # https://github.com/Normation/rudder/blob/master/contributing/webapp.md#test-ldap-connection to set it up
    # /opt/ApacheDirectoryStudio/ApacheDirectoryStudio

    # at every startup, run:
    # $rudder_dir/rudder-tests/rtf platform setup debian9_dev
