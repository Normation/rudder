#!/bin/sh

set -xe

#Check if arg are used
if [ $# -ne 1 ]
then
  echo "usage: $0 ServerIP"
  exit
else
    SERVER_INSTANCE_IP=$1 #ServerIP
fi

#APT configuration
echo "deb http://ftp.fr.debian.org/debian/ lenny main contrib non-free" > /etc/apt/sources.list
echo "deb-src http://ftp.fr.debian.org/debian/ lenny main contrib non-free" >> /etc/apt/sources.list
echo "deb http://security.debian.org/ lenny/updates main contrib non-free" >> /etc/apt/sources.list
echo "deb-src http://security.debian.org/ lenny/updates main contrib non-free" >> /etc/apt/sources.list
echo "deb file:/var/2.2 lenny main contrib non-free" >> /etc/apt/sources.list
aptitude update

##Configure locale
sed -i 's/# en_US.UTF-8/en_US.UTF-8/g' /etc/locale.gen
/usr/sbin/locale-gen

##Packages minimum
aptitude install -y debian-archive-keyring

#Rudder Installation
aptitude install -y --allow-untrusted rudder-agent

##Configure Rudder
echo "$SERVER_INSTANCE_IP" > /var/rudder/cfengine-community/policy_server.dat

##Wait for the root server to be ready
i=1
TIMEOUT=120
set +e
/usr/bin/curl -f -o - http://${SERVER_INSTANCE_IP}/uuid > /dev/null
while [ $? -ne 0 ]
do
	if [ $i -ge $TIMEOUT ]
	then
		echo "Root server didn't share the UUID in $i seconds"
		exit 1
	fi

	sleep 1
	i=`expr $i + 1`
	/usr/bin/curl -f -o - http://${SERVER_INSTANCE_IP}/uuid > /dev/null
done
set -e

##Start cfengine deamon
/etc/init.d/rudder-agent start

##Force send information
/opt/rudder/sbin/cf-agent -IK

