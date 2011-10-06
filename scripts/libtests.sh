#!/bin/sh
#Test library


#Check if nodes have been inventoried
serverInventory()
{
	wait=1
	TEST_INVENTORY1=`grep "$NODE1_INSTANCE_IP" -lR /var/rudder/inventories/{incoming,received} | wc -l`
	while [ $TEST_INVENTORY1 -ne 1 ]
	do
		if [ $wait -ge 120 ]
		then
			echo ""
			echo "Inventory: Fail" > /dev/stdout
			return 1
		fi

		if [ $wait -eq 1 ]
		then
			echo -n "Waiting for inventory"
		else
			echo -n "."
		fi

		wait=`expr $wait + 1`
		sleep 1
		TEST_INVENTORY1=`grep "$NODE1_INSTANCE_IP" -lR /var/rudder/inventories/{incoming,received} | wc -l`
	done

	echo ""
	echo "Inventory: OK" > /dev/stdout
	return 0
}
#Check if Cfengine is up
nodeCFE()
{
TEST_CFENGINE1=`ps aux | grep [c]f-serverd | wc -l`
TEST_CFENGINE2=`ps aux | grep [c]f-execd | wc -l`
if [ $TEST_CFENGINE1 -eq 1 -a $TEST_CFENGINE2 -eq 1 ]
then
	echo "Cfengine: OK" > /dev/stdout
	return 0
fi
echo "Cfengine: Fail (not all components running)" > /dev/stderr
return 1
}
