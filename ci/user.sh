#!/bin/sh

# Jenkins user with correct uid
USER_ID=$1
mkdir /home/jenkins
useradd -r -u $USER_ID -d /home/jenkins jenkins
chown jenkins /home/jenkins
