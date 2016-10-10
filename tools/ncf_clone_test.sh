#!/bin/sh
#
# This script makes sure that CFEngine is installed, clones the NCF repository
# in ${TEST_WORKDIR} and runs the test suite.
#
# The script will exit with the same return code as the test suite utility.
# (if != 0, it means at least one test did fail)
#
# Matthieu CERDA <matthieu.cerda@normation.com> Wed, 20 Nov 2013 19:07:28 +0100

set -e

# Disable any internationalization
LC_ALL=C
unset LANG

# Configuration
TEST_WORKDIR=/tmp
TEST_DEPENDENCIES="curl lsb-release htop python-jinja2"

# Default values
BRANCH="master"
REPO="https://github.com/Normation/ncf.git"

# CFEngine source to use
# can be:
# "rudder" for a Rudder agent
# "cfengine" for a CFEngine agent
SOURCE="$1"

# Version to use
# Rudder versions use the same notation as rtf
# CFEngine versions must by of the x.y.z form
VERSION="$2"

APT_GET="/usr/bin/env DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get"
YUM="/usr/bin/yum --quiet --setopt=exit_on_lock=True"

# ncf branch to test
if [ "z$3" != "z" ]; then
	BRANCH="$3"
fi

# Repository to clone
if [ "z$4" != "z" ]; then
	REPO="$4"
fi

# Documentation
usage() {
  echo "Usage $0 [rudder|cfengine] version [branch] [repository]"
	echo ""
  echo "  Setup an agent and starts the ncf tests. Should be launched only on disposable test machines"
	echo "  as some of the tests may modify the system."
	echo ""
	echo "  rudder|cfengine: binary package source"
	echo ""
	echo "  version: version to use, for example"
	echo "    for cfengine source: 3.6.5, 3.7.3"
	echo "    for rudder source: 3.1, 3.0.10, ci/3.2.14, 3.1-nightly"
	echo ""
	echo "  branch: ncf branch to use (default: master)"
	echo ""
	echo "  repository: repository to clone (default: https://github.com/Normation/ncf.git)"
  exit 1
}

install_dependencies() {
	if type apt-get >/dev/null 2>/dev/null; then
	    ${APT_GET} update
		${APT_GET} -y install ${TEST_DEPENDENCIES}
	elif type yum >/dev/null 2>/dev/null; then
	  	${YUM} install ${TEST_DEPENDENCIES}
	else
		echo "Unsupported platform."
		exit 1
    fi
}

install_rudder_agent() {
	cd ${TEST_WORKDIR}
	
	curl -sO "https://www.rudder-project.org/tools/rudder-setup"
	sh rudder-setup setup-agent ${VERSION}
	
	# Put CFEngine binaries in the default PATH
	export PATH="${PATH}:/var/rudder/cfengine-community/bin/"
}

install_cfengine_agent() {
	cd ${TEST_WORKDIR}
	
	if type dpkg >/dev/null 2>/dev/null; then
	  curl -s0 -o cfengine-community.deb "https://cfengine-package-repos.s3.amazonaws.com/community_binaries/cfengine-community_${VERSION}-1_amd64.deb"
		dpkg -i cfengine-community.deb
	elif type rpm >/dev/null 2>/dev/null; then
	  	curl -s0 -o cfengine-community.rpm "https://cfengine-package-repos.s3.amazonaws.com/community_binaries/cfengine-community_${VERSION}-1.x86_64.rpm"
		rpm -i cfengine-community.rpm
	else
		echo "Unsupported platform."
		exit 1
    fi
}

if [ "$#" -ne 2 ] && [ "$#" -ne 3 ] && [ "$#" -ne 4 ]; then
	usage
fi

# Install dependencies
install_dependencies

# Install the agent
if [ "${SOURCE}" = "rudder" ]; then
	install_rudder_agent
	echo "Using CFEngine binaries from Rudder ${VERSION} packages"
elif [ "${SOURCE}" = "cfengine" ]; then
	install_cfengine_agent
	echo "Using CFEngine binaries from CFEngine ${VERSION} packages"
else
    echo "Unknown package source."
	exit 1
fi

# Clone NCF, if missing
if [ ! -x ${TEST_WORKDIR}/ncf ]
then
	cd ${TEST_WORKDIR}
	git clone ${REPO} ncf
fi

# Checkout correct branch and update it
cd ${TEST_WORKDIR}/ncf
git checkout ${BRANCH}
git pull origin ${BRANCH}

# Start the test suite
echo "Beginning tests, using CFEngine version \"`cf-agent -V`\""
make test-unsafe
