#!/bin/sh

#VARS
TREE="/tmp/arboTMP"
HUDSON_BASE="/var/lib/hudson/jobs"
HUDSON_RUDDER_CORE="$HUDSON_BASE/Rudder/workspace/rudder-core"
HUDSON_RUDDER_WEB="$HUDSON_BASE/Rudder/workspace/rudder-web"
HUDSON_INVENTORY="$HUDSON_BASE/ldap-inventory/workspace/inventory-core"
HUDSON_INVENTORY_WEB="$HUDSON_BASE/ldap-inventory/workspace/inventory-provisioning/inventory-web"
HUDSON_POLICY_TEMPLATES="$HUDSON_BASE/policy-templates/workspace"

#Efface ancien script
rm -rvf $TREE/*

#Preparation repertoire destination
mkdir -p $TREE/opt/rudder/etc/
mkdir -p $TREE/opt/rudder/bin/
mkdir -p $TREE/opt/rudder/src/main/resources
mkdir -p $TREE/opt/rudder/src/test/resources

mkdir -p /var/rudder/{backup,files,lock}
mkdir -p /opt/rudder/{share,tools}
mkdir -p /var/rudder/inventories/{incoming,received,historical}
mkdir -p /var/log/rudder/{apache2,ldap,reports,webapp}

mkdir -p $TREE/opt/normation/etc/openldap/schema/
mkdir -p $TREE/opt/rudder/jetty/jetty7/webapps
mkdir -p $TREE/opt/rudder/jetty/jetty7/var

mkdir -p $TREE/var/lib/openldap-data/

#Copie fichiers dans repertoire destination
cp $HUDSON_RUDDER_CORE/src/main/resources/bootstrap.ldif $TREE/opt/rudder/src/main/resources/
cp $HUDSON_RUDDER_CORE/src/main/resources/sample-data.ldif $TREE/opt/rudder/src/main/resources/
cp $HUDSON_RUDDER_CORE/src/main/resources/reportsSchema.sql $TREE/opt/rudder/src/main/resources/
cp $HUDSON_RUDDER_CORE/src/test/resources/testSchema.sql $TREE/opt/rudder/src/test/resources/
cp $HUDSON_RUDDER_CORE/src/test/resources/reportsInfo.sql $TREE/opt/rudder/src/test/resources/

cp $HUDSON_INVENTORY/src/main/ldap/slapd.conf $TREE/opt/normation/etc/openldap/
cp $HUDSON_INVENTORY/src/main/ldap/DB_CONFIG $TREE/var/lib/openldap-data/
cp $HUDSON_INVENTORY/src/main/ldap/inventory.schema $TREE/opt/normation/etc/openldap/schema/
cp $HUDSON_RUDDER_CORE/src/main/resources/rudder.schema $TREE/opt/normation/etc/openldap/schema/

cp -r $HUDSON_POLICY_TEMPLATES/policies $TREE/opt/rudder/share/policy-templates
cp -r $HUDSON_POLICY_TEMPLATES/tools $TREE/opt/rudder/share/tools

cp $HUDSON_RUDDER_CORE/src/test/resources/script/cfe-red-button.sh $TREE/opt/rudder/bin/

cp -r $HUDSON_RUDDER_CORE/src/main/resources/licenses $TREE/opt/rudder/etc/licenses
cp $HUDSON_RUDDER_CORE/src/main/resources/licenses.xml $TREE/opt/rudder/etc/licenses.xml
cp $HUDSON_RUDDER_CORE/src/main/resources/reportsInfo.xml $TREE/opt/rudder/etc/
cp $HUDSON_RUDDER_WEB/src/main/resources/configuration.properties $TREE/opt/rudder/etc/rudder-web.properties
cp $HUDSON_INVENTORY_WEB/src/main/resources/configuration.properties $TREE/opt/rudder/etc/inventory-web.properties
cp $HUDSON_RUDDER_WEB/src/main/resources/logback.xml.sample $TREE/opt/rudder/etc/logback.xml

cp $HUDSON_RUDDER_WEB/target/rudder-web-*.war $TREE/opt/rudder/jetty/jetty7/webapps/rudder.war
cp $HUDSON_INVENTORY_WEB/target/inventory-web-*.war $TREE/opt/rudder/jetty/jetty7/webapps/endpoint.war

#Archivage
tar -C $TREE -cvf $TREE/rudder.tar opt/ var/
