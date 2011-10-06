#!/bin/sh

set -ex

#Check if arg are used
if [ $# -ne 3 ]
then
  echo "usage: $0 hostname serverIP AllowedNetwork1"
  exit
else
    SERVER_INSTANCE_HOST=$1 #Hostname
    SERVER_INSTANCE_IP=$2 #ServerIP
    ALLOWEDNETWORK[0]=$3 #ServerAllowed
fi
#VARS
DEMOSAMPLE="no"
LDAPRESET="yes"
INITPRORESET="yes"
#Configure locale
sed -i 's/# en_US.UTF-8/en_US.UTF-8/g' /etc/locale.gen
/usr/sbin/locale-gen
#APT configuration
echo "deb http://ftp.fr.debian.org/debian/ lenny main contrib non-free" > /etc/apt/sources.list
echo "deb-src http://ftp.fr.debian.org/debian/ lenny main contrib non-free" >> /etc/apt/sources.list
echo "deb http://security.debian.org/ lenny/updates main contrib non-free" >> /etc/apt/sources.list
echo "deb-src http://security.debian.org/ lenny/updates main contrib non-free" >> /etc/apt/sources.list
echo "deb file:/var/2.2 lenny main contrib non-free" >> /etc/apt/sources.list
##Accept Java Licence
echo sun-java6-jre shared/accepted-sun-dlj-v1-1 select true | /usr/bin/debconf-set-selections
aptitude update
#Packages minimum
aptitude install -y debian-archive-keyring
#Rudder Installation
aptitude install -y --allow-untrusted rudder-server-root
/opt/rudder/bin/rudder-init.sh $SERVER_INSTANCE_HOST $SERVER_INSTANCE_IP $DEMOSAMPLE $LDAPRESET $INITPRORESET ${ALLOWEDNETWORK[0]}
