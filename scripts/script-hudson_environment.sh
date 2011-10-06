#!/bin/sh

set -xe

########
# VARS #
########

CMD_IP='/sbin/ifconfig eth0 | fgrep "inet ad" | cut -f2 -d":" | cut -f1 -d" "'

#Amazon EC2 configuration
export JAVA_HOME=/var/lib/hudson/tools/Java_1.6.0.21_depuis_Sun_automatiquement
export EC2_HOME=/opt/ec2-api-tools
export EC2_PRIVATE_KEY=$EC2_HOME/conf/pk-ZOHOLQ4RM6UNUP5PADRYWGFHLSBAXNF6.pem
export EC2_CERT=$EC2_HOME/conf/cert-ZOHOLQ4RM6UNUP5PADRYWGFHLSBAXNF6.pem
export EC2_URL=https://ec2.eu-west-1.amazonaws.com


##################################
# Set up traps to be run on EXIT #
##################################

declare -a on_exit_items
EXITCODE=0

function on_exit_error()
{
	# Run traps then exit with an error status code
	EXITCODE=1
}

# Run all traps in reverse order
function on_exit()
{
	# Don't fail on all errors here, we must try and cleanup as much as possible
	set +e

	local i=${#on_exit_items[*]}
	i=`expr $i - 1`
	while [ $i -ge 0 ]
    do
		cmd=${on_exit_items[$i]}
        echo "on_exit: $cmd"
        eval $cmd
		EXITCODE=`expr $EXITCODE + $?`
		i=`expr $i - 1`
    done
	set -e
	return $EXITCODE
}

function add_on_exit()
{
    local n=${#on_exit_items[*]}
    on_exit_items[$n]="$*"
    if [[ $n -eq 0 ]]; then
        echo "Setting trap"
        trap on_exit EXIT
        trap on_exit_error ERR
    fi
}


#Configure new security group
/opt/ec2-api-tools/bin/ec2-add-group normation-ci -d "Continuous Integration Group"
add_on_exit /opt/ec2-api-tools/bin/ec2-delete-group normation-ci
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 5432 -s 127.0.0.1/0
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 80 -s 83.157.145.147/24
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 80 -s 88.190.19.131/24
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 22 -s 83.157.145.147/24
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 22 -s 88.190.19.131/24
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 442 -s 83.157.145.147/24
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 442 -s 88.190.19.131/24

#Rudder Server Root Instance Management
##Create instance
/opt/ec2-api-tools/bin/ec2-run-instances ami-8398b3f7 --group normation-ci -k normation-test
##Catch server instance number
SERVER_INSTANCE_NUM=`/opt/ec2-api-tools/bin/ec2-describe-instances | tail -n1 | awk '{print $2}'`
add_on_exit /opt/ec2-api-tools/bin/ec2-terminate-instances $SERVER_INSTANCE_NUM

/opt/ec2-api-tools/bin/ec2-create-tags $SERVER_INSTANCE_NUM --tag Name=RudderServerRoot --tag Workgroup=HudsonTEST

##Wait for host and ip generation and catch them
sleep 30
SERVER_INSTANCE_HOST=`/opt/ec2-api-tools/bin/ec2-describe-instances --hide-tags -F tag-value=HudsonTEST | tail -n1 | awk '{print $4}'`
sleep 20
SERVER_INSTANCE_IP=`ssh -i ${EC2_HOME}/conf/normation-test.pem root@$SERVER_INSTANCE_HOST "$CMD_IP"`
echo -e "SERVER_INSTANCE_NUM=$SERVER_INSTANCE_NUM\nSERVER_INSTANCE_HOST=$SERVER_INSTANCE_HOST\tSERVER_INSTANCE_IP=$SERVER_INSTANCE_IP"
##Copy apt directory to instance
echo "Transfert .deb"
scp -r -i ${EC2_HOME}/conf/normation-test.pem /var/www/apt-repos/2.2/ root@$SERVER_INSTANCE_HOST:/var/
##Add flow in security group
echo "Make firewall rules"
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 5308 -s ${SERVER_INSTANCE_IP}/32
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 5309 -s ${SERVER_INSTANCE_IP}/32
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 80 -s ${SERVER_INSTANCE_IP}/32
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 514 -s ${SERVER_INSTANCE_IP}/32

#Rudder Node1 Instance Management
##Create instance
/opt/ec2-api-tools/bin/ec2-run-instances ami-8398b3f7 --group normation-ci -k normation-test
##Catch node1 instance number
NODE1_INSTANCE_NUM=`/opt/ec2-api-tools/bin/ec2-describe-instances | tail -n1 | awk '{print $2}'`
add_on_exit /opt/ec2-api-tools/bin/ec2-terminate-instances $NODE1_INSTANCE_NUM

/opt/ec2-api-tools/bin/ec2-create-tags $NODE1_INSTANCE_NUM --tag Name=RudderNode1 --tag Workgroup=HudsonTEST

##Wait for host and ip generation and catch them
sleep 30
NODE1_INSTANCE_HOST=`/opt/ec2-api-tools/bin/ec2-describe-instances --hide-tags -F tag-value=HudsonTEST | tail -n1 | awk '{print $4}'`
sleep 20
NODE1_INSTANCE_IP=`ssh -i ${EC2_HOME}/conf/normation-test.pem root@$NODE1_INSTANCE_HOST "$CMD_IP"`
echo -e "NODE1_INSTANCE_NUM=$NODE1_INSTANCE_NUM\nNODE1_INSTANCE_HOST=$NODE1_INSTANCE_HOST\tNODE1_INSTANCE_IP=$NODE1_INSTANCE_IP"
##Copy apt directory to instance
echo "Transfert .deb"
scp -r -i ${EC2_HOME}/conf/normation-test.pem /var/www/apt-repos/2.2/ root@$NODE1_INSTANCE_HOST:/var/
#Add flow in security group
echo "Make firewall rules"
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 5308 -s ${NODE1_INSTANCE_IP}/32
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 5309 -s ${NODE1_INSTANCE_IP}/32
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 80 -s ${NODE1_INSTANCE_IP}/32
/opt/ec2-api-tools/bin/ec2-authorize normation-ci -P tcp -p 514 -s ${NODE1_INSTANCE_IP}/32

#Installation
##Transfer install scripts and execute them
echo "Server Install Script Transfert"
scp -i ${EC2_HOME}/conf/normation-test.pem ${WORKSPACE}/scripts/script-hudson_install-server-package.sh root@$SERVER_INSTANCE_HOST:/tmp/
echo "Node1 Install Script Transfert"
scp -i ${EC2_HOME}/conf/normation-test.pem ${WORKSPACE}/scripts/script-hudson_install-node1-package.sh root@$NODE1_INSTANCE_HOST:/tmp/
echo "Server Install Script Execution"
ssh -i ${EC2_HOME}/conf/normation-test.pem root@$SERVER_INSTANCE_HOST "/tmp/script-hudson_install-server-package.sh $SERVER_INSTANCE_HOST $SERVER_INSTANCE_IP $NODE1_INSTANCE_IP"
echo "Node1 Install Script Execution"
ssh -i ${EC2_HOME}/conf/normation-test.pem root@$NODE1_INSTANCE_HOST "/tmp/script-hudson_install-node1-package.sh $SERVER_INSTANCE_IP"

#Tests
scp -i ${EC2_HOME}/conf/normation-test.pem ${WORKSPACE}/scripts/libtests.sh root@$SERVER_INSTANCE_HOST:/tmp/
scp -i ${EC2_HOME}/conf/normation-test.pem ${WORKSPACE}/scripts/libtests.sh root@$NODE1_INSTANCE_HOST:/tmp/
scp -i ${EC2_HOME}/conf/normation-test.pem ${WORKSPACE}/scripts/testServer.sh root@$SERVER_INSTANCE_HOST:/tmp/
scp -i ${EC2_HOME}/conf/normation-test.pem ${WORKSPACE}/scripts/testNodes.sh root@$NODE1_INSTANCE_HOST:/tmp/
ssh -i ${EC2_HOME}/conf/normation-test.pem root@$NODE1_INSTANCE_HOST "/tmp/testNodes.sh"
ssh -i ${EC2_HOME}/conf/normation-test.pem root@$SERVER_INSTANCE_HOST "/tmp/testServer.sh $NODE1_INSTANCE_IP"

