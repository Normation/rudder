#!/bin/bash
#
# Copyright 2010-2014 (c) Normation SAS
# Author: Jonathan CLARKE <jonathan.clarke@normation.com>
# Author: Matthieu CERDA <matthieu.cerda@normation.com>
#
# This script sends a file to a remote server and archives the local copy if
# transmission was successful.
#
# Return values:
## 255:     Error in script arguments
## 1 to XX: curl (or other send command) exit status

# Pre-flight checks

usage() {
  echo "ERROR: Incorrect usage"
  echo "Example: $0 server filename archiveDir failedDir"
}

## Check number of arguments
if [ ${#} -ne 4 ]; then
  usage
  exit 255
fi

# Configuration

SERVER=${1}
FILENAME=${2}
ARCHIVEDIR=${3}
FAILEDDIR=${4}
BASENAME=$(basename ${2})

if [ -x "/opt/rudder/bin/curl" ]; then
  CURL_BINARY="/opt/rudder/bin/curl"
else
  CURL_BINARY="/usr/bin/curl"
fi
# Maximum number of minute to wait before assuming the signature is missing
MAX_SIGNATURE_WAIT=1

# End of configuration

# 1 - Create the necessary directories if needed
for i in "${ARCHIVEDIR}" "${FAILEDDIR}"
do
  mkdir -p ${i}
done

# 2 - Look for signature file
SIGNATURE_OPT=""

FILENAME_SIGN="${FILENAME%%.gz}.sign"


if [ -f "${FILENAME_SIGN}" ]
then
  SIGNATURE_OPT="-F signature=@${FILENAME_SIGN}"
else
  # 3.1 - No signature and file timestamp < 2mn -> wait for it
  if [ $(find "${FILENAME}" -mmin "+${MAX_SIGNATURE_WAIT}" | wc -l) = "0" ]
  then
    exit 0
  fi
fi

# 3 - If the file appears to be compressed, attempt to uncompress it
#    ${VARIABLE##*.} extracts the file extension
if [ "${BASENAME##*.}" = "gz" ]
then
  gzip --force --quiet --decompress ${FILENAME}
  UNZIP_COMMAND_RET=$?
  if [ $UNZIP_COMMAND_RET -ne 0 ]
  then
    echo "ERROR: failed to decompress inventory in ${FILENAME} az gzip, putting it in the failed directory"
    mv "${FILENAME}" "${FAILEDDIR}/$(basename ${FILENAME})-$(date --rfc-3339=date)"
    [ -f "${FILENAME_SIGN}" ] && mv "${FILENAME_SIGN}" "${FAILEDDIR}/$(basename ${FILENAME_SIGN})-$(date --rfc-3339=date)"
    # Since we have not even tried to upload, there is not HTTP Return code
    # We exit with zero not to break the processing of the remaining inventory.
    exit 0
  fi
  # ${VARIABLE%.*} removes the last extension of the file, here: .gz
  FILENAME="${FILENAME%.*}"
fi

# 4 - Send the file
HTTP_CODE=$(${CURL_BINARY} --proxy '' -f ${SIGNATURE_OPT} -F "file=@${FILENAME}" -o /dev/null -w '%{http_code}' ${SERVER})
SEND_COMMAND_RET=$?

# 5.1 - Wait if queue is overloaded
if [ ${SEND_COMMAND_RET} -eq 7 -o "${HTTP_CODE}" -eq 503 ]
then
  # Endpoint is too busy (HTTP_CODE == 503), wait for next run before trying other inventories
  echo "WARNING: queue is overloaded, next inventories will be sent during next round"
  exit 1
fi
# 5.2 - Abort if sending failed
if [ ${SEND_COMMAND_RET} -eq 7 -o "${HTTP_CODE}" -ge 500 ]
then
  # Endpoint is unavailable (ret == 7) for any unknown reason
  # Just leave this file in the incoming directory, it will be retried soon
  echo "WARNING: Unable to send ${FILENAME}, inventory endpoint is temporarily unavailable, will retry later"
  echo "This often happens due to rate-throttling in the endpoint to save on memory consumption. This is standard behavior."
  exit ${SEND_COMMAND_RET}
# 5.3 - Put aside if inventory has a problem and continue
elif [ ${SEND_COMMAND_RET} -ne 0 -o "${HTTP_CODE}" -ge 400 ]
then
  # The server refused our inventory (or there was a problem) (401 -> signature failed, 412 -> inventory structure failed)
  echo "ERROR: Failed to send inventory ${FILENAME}, putting it in the failed directory"
  mv "${FILENAME}" "${FAILEDDIR}/`basename ${FILENAME}`-$(date --rfc-3339=date)"
  mv "${FILENAME_SIGN}" "${FAILEDDIR}/`basename ${FILENAME_SIGN}`-$(date --rfc-3339=date)"
fi

# 6 - Sending succeeded, archive original file
mv "${FILENAME}" "${ARCHIVEDIR}"
[ -f "${FILENAME}.sign" ] && mv "${FILENAME}.sign" "${ARCHIVEDIR}"

# 7 - That's all, folks
exit 0
