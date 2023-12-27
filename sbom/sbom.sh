#!/bin/bash

set -ex
export LANG=C

# TODO create RSA key for signature, put in jenkins
# publish in repo

# TODO faire du README une doc supply-chain de Rudder

# splitter par package (server, relay, agent-windows)

# FXIME : version des outils Rust comme dans les sources

# Layout du repo ???

# sources/

# sbom/
#   8.0.4/
#     spdx/
#     cyclonedx/

# Should we work on private repositories
PRIVATE="false"
# Should we try to update/clone repos?
OFFLINE="false"
# Should we sign resulting SBOM files?
SIGN="false"
# Target, in the X.Y.Z form for Rudder main components
VERSION=""

while getopts 'phosv:' opt; do
  case "$opt" in
    p)
      PRIVATE="true"
      ;;
    v)
      VERSION="$OPTARG"
      ;;
    s)
      SIGN="true"
      ;;
    o)
      OFFLINE="true"
      ;;
    *)
      echo "Usage: $(basename $0) [-p] [-v VERSION]"
      exit 1
      ;;
  esac
done

if [ "${VERSION}" = "" ]; then
  # FIXME: get from repo latest tag
  echo "Missing -v version argument"
  exit 1
fi
export VERSION

checkout() {
  repo="$1"
  tag="$2"
  if [ ! -d "${repo}" ] && [ "${OFFLINE}" = "false" ]; then
    git clone "https://github.com/Normation/${repo}.git"
  fi
  cd "${repo}"
  if [ "${OFFLINE}" = "false" ]; then
    git fetch
  fi
  git checkout "$tag"
  cd -
}

# Main, i.e. non-plugin parts
main_repos() {
  rm -rf sbom
  mkdir sbom
  checkout rudder "${VERSION}"

  # maven (Scala/Java)
  mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom --file rudder/webapp/sources/pom.xml
  mv rudder/webapp/sources/target/bom.json sbom/backend.json

  # npm (JS)
  cd rudder/webapp/sources/rudder/rudder-web/src/main/
  npm_config_loglevel=error npm ci --no-audit
  npx makebom . -o bom.json
  cd -
  mv rudder/webapp/sources/rudder/rudder-web/src/main/bom.json sbom/frontend.json

  # cargo (Rust)
  cargo cyclonedx --all --format json --manifest-path rudder/relay/sources/relayd/Cargo.toml
  mv rudder/relay/sources/relayd/bom.json sbom/relayd.json
  cargo cyclonedx --all --format json --manifest-path rudder/policies/rudderc/Cargo.toml
  mv rudder/policies/rudderc/bom.json sbom/rudderc.json

  if [ "${PRIVATE}" = "true" ]; then
    checkout rudder-agent-windows "${VERSION}"
    # dotnet (F#)
    dotnet CycloneDX --exclude-dev --json rudder-agent-windows/common/initial-policy/ncf/rudderLib/rudderLib.sln --out sbom --filename windows.json
  fi

  # Aggregate everything
  cyclonedx merge --input-files sbom/*.json --output-file "rudder-${VERSION}.cdx.json"
}

plugin_repo() {
  repo="$1"
  checkout "${repo}" "master"
  tags=$(git -C rudder-plugins tag | grep -E "^[a-z-]+-${VERSION}-.*")
  for tag in ${tags}; do
    checkout "${repo}" "${tag}"
    plugin=$(echo "${tag}" | sed -E "s/^([a-z-]+)-${VERSION}-.*\$/\1/")
    cd "${repo}/${plugin}"
    # Only work on webapp plugins
    if [ -f pom-template.xml ]; then
      make -d generate-pom
      mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom --file pom.xml
      mv target/bom.json "../../rudder-${tag}.cdx.json"
    fi
    cd -
  done
}

finish_sbom() {
  # stem without extension
  file="$1"
  cyclonedx convert --input-file "${file}.cdx.json" --output-format xml > "${file}.cdx.xml"
  # Can then be signed
  if [ "${SIGN}" = "true" ]; then
    cyclonedx sign bom "${file}.cdx.xml"
  fi
  # Compress
  xz rudder-*.json
}


main_repos
plugin_repo "rudder-plugins"
plugin_repo "rudder-plugins-private"

