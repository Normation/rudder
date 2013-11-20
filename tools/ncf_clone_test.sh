#!/bin/sh
#
# This script makes sure that CFEngine is installed (from the Normation
# repository), clones the NCF repository in ${TEST_WORKDIR} and runs the
# test suite.
#
# The script will exit with the same return code as the test suite utility.
# (if != 0, it means at least one test did fail)
#
# Matthieu CERDA <matthieu.cerda@normation.com> Wed, 20 Nov 2013 19:07:28 +0100

set -e

## Configuration

TEST_WORKDIR=/tmp
TEST_DEPENDENCIES="curl lsb-release"

# Disable any internationalization
LC_ALL=C
unset LANG

## Runtime

# 1 - Install the dependencies
apt-get update
apt-get -y install ${TEST_DEPENDENCIES}

# 2 - Install CFEngine from the Normation repository if necessary
# Note: The second apt-get update is mandatory, need curl before adding the new apt source
if ! dpkg-query -W --showformat='${Status}\n' cfengine-community | grep -q "install ok installed"
then
	curl http://www.normation.com/cfengine-repo/gpg.key | apt-key add -
	echo "deb http://www.normation.com/cfengine-repo/apt $(lsb_release -cs) main" > /etc/apt/sources.list.d/cfengine-community.list
	apt-get update
	apt-get -y install cfengine-community
fi

# 3 - Clone (or update) NCF
if [ ! -x ${TEST_WORKDIR}/ncf ]
then
	cd ${TEST_WORKDIR}
	git clone https://github.com/Normation/ncf.git
else
	cd ${TEST_WORKDIR}/ncf
	git pull origin master
fi

# 4 - Start the test suite
cd ${TEST_WORKDIR}/ncf
echo "Beginning tests, using CFEngine version \"`/var/cfengine/bin/cf-agent -V`\""
make
