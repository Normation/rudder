#!/bin/sh

#VARS


#Configuration apt
echo "deb http://ftp.fr.debian.org/debian/ lenny main contrib non-free" > /etc/apt/sources.list
echo "deb-src http://ftp.fr.debian.org/debian/ lenny main contrib non-free" >> /etc/apt/sources.list
echo "deb http://security.debian.org/ lenny/updates main contrib non-free" >> /etc/apt/sources.list
echo "deb-src http://security.debian.org/ lenny/updates main contrib non-free" >> /etc/apt/sources.list
echo "deb http://volatile.debian.org/debian-volatile lenny/volatile main contrib non-free" >> /etc/apt/sources.list
echo "deb-src http://volatile.debian.org/debian-volatile lenny/volatile main contrib non-free" >> /etc/apt/sources.list
echo "deb http://www.normation.com/apt/ lenny main" >> /etc/apt/sources.list
echo "deb http://www.backports.org/debian lenny-backports main contrib non-free" >> /etc/apt/sources.list
##Accept Java Licence
echo sun-java6-jre shared/accepted-sun-dlj-v1-1 select true | /usr/bin/debconf-set-selections

aptitude update

#paquets minimum
aptitude -y --allow-untrusted install openssh-server postgresql normation-openldap sun-java6-jre

#Installation jetty
wget http://dist.codehaus.org/jetty/jetty-hightide-7.2.2/jetty-hightide-7.2.2.v20101205.tar.gz
tar -C /opt -xvvzf jetty-hightide-7.2.2.v20101205.tar.gz

#Creation lien symbolique
mkdir -p /opt/rudder/jetty
ln -s /opt/jetty-hightide-7.2.2.v20101205 /opt/rudder/jetty/jetty7


#Desarchivage
tar -C / -xvf /tmp/rudder.tar

#Modifications fichiers de conf
##LDAP
sed -i 's%BASE_PATH/var%opt/normation/var%g' /opt/normation/etc/openldap/slapd.conf
sed -i 's%BASE_PATH%opt/normation/etc/openldap%g' /opt/normation/etc/openldap/slapd.conf
sed -i 's%inventory.schema%inventory.schema\ninclude\t\t/opt/normation/etc/openldap/schema/rudder.schema%g' /opt/normation/etc/openldap/slapd.conf
sed -i 's%/usr/lib/ldap%/opt/normation/libexec/openldap/%g' /opt/normation/etc/openldap/slapd.conf
##Jetty
sed -i 's%# JAVA_OPTIONS%\nJAVA=/usr/lib/jvm/java-6-sun/bin/java\n\n# JAVA_OPTIONS%g' /opt/rudder/jetty/jetty7/bin/jetty.sh
sed -i 's%# JETTY_HOME%\nJAVA_OPTIONS="$JAVA_OPTIONS\n-server\n-Xms512m -Xmx512m\n-XX:PermSize=64m -XX:MaxPermSize=64m\n-XX:+CMSPermGenSweepingEnabled\n-XX:+CMSClassUnloadingEnabled\n-Drudder.configFile=/opt/rudder/etc/rudder-web.properties\n-Dinventoryweb.configFile=/opt/rudder/etc/inventory-web.properties\n-Dlogback.configurationFile=/opt/rudder/etc/logback.xml\n"\n\n# JETTY_HOME%g' /opt/rudder/jetty/jetty7/bin/jetty.sh
sed -i 's%# JETTY_PORT%\nJETTY_HOME=/opt/normation/jetty/jetty7/\n\n# JETTY_PORT%g' /opt/rudder/jetty/jetty7/bin/jetty.sh
sed -i 's%# JETTY_PID%\nJETTY_RUN=${JETTY_HOME}/var/\n\n# JETTY_PID%g' /opt/rudder/jetty/jetty7/bin/jetty.sh
sed -i 's%# JETTY_USER%\nJETTY_ARGS="OPTIONS=Server"\n\n# JETTY_USER%g' /opt/rudder/jetty/jetty7/bin/jetty.sh
##Lanceur Jetty
ln -s /opt/rudder/jetty/jetty7/bin/jetty.sh /etc/init.d/jetty

#Bootstrap annuaire LDAP
/etc/init.d/slapd stop
/opt/normation/sbin/slapadd -l /opt/rudder/src/main/resources/bootstrap.ldif
/opt/normation/sbin/slapadd -l /opt/rudder/src/main/resources/sample-data.ldif
/etc/init.d/slapd start

#Configuration Postgres
##Users et BD
su postgres -c "psql -c \"CREATE USER rudder WITH PASSWORD 'Normation'\""
su postgres -c "psql -c \"CREATE DATABASE rudder WITH OWNER = rudder\""
#su postgres -c "psql -c \"CREATE USER test WITH PASSWORD 'test'\""
#su postgres -c "psql -c \"CREATE DATABASE test WITH OWNER = test\""

##Fichier passwords
echo "localhost:5432:rudder:rudder:Normation" > ~/.pgpass
#echo "localhost:5432:test:test:test" >> ~/.pgpass
chmod 600 ~/.pgpass

##Remplissage BD
psql -U rudder -h localhost -d rudder -f /opt/rudder/src/main/resources/reportsSchema.sql
#psql -U test -h localhost -d test -f /opt/rudder/src/test/resources/testSchema.sql


#Lancement de jetty
/etc/init.d/jetty start
