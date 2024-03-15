#!/bin/bash

set -ex
export LANG=C

# TODO create RSA key for signature, put in jenkins
# publish in repo

# TODO faire du README une doc supply-chain de Rudder

# FXIME : version des outils Rust comme dans les sources

# Should we work on private repositories
PRIVATE="false"
# Should we sign resulting SBOM files?
SIGN="false"
# Target, in the X.Y.Z form for Rudder main components
VERSION=""

MAVEN_CYCLONEDX_VER="2.7.11"

while getopts 'phsv:' opt; do
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
  version="$2"
  if [ ! -d "${repo}" ]; then
    git clone "https://github.com/Normation/${repo}.git"
  fi
  cd "${repo}"
  git fetch
  [ -f "init-repo.sh" ] && ./init-repo.sh
  # handle retag, take latest
  tag=$(git tag | grep "${version}" | sort | tail -n1)
  [ -z "${tag}" ] && tag="${version}"
  git checkout "$tag"
  git reset --hard
  # like in sources
  find . -name "Cargo\.*" -exec sed -i "s/version = \"0.0.0-dev\"/version = \"${version}\"/" {} \;
  cd -
}

server() {
  checkout rudder "${VERSION}"

  # maven (Scala/Java)
  mvn "org.cyclonedx:cyclonedx-maven-plugin:${MAVEN_CYCLONEDX_VER}:makeAggregateBom" --file rudder/webapp/sources/pom.xml
  mv rudder/webapp/sources/target/bom.json target/tmp_server-backend.json

  # npm (JS)
  cd rudder/webapp/sources/rudder/rudder-web/src/main/
  npm_config_loglevel=error npm ci --no-audit
  npx makebom . -o bom.json
  cd -
  mv rudder/webapp/sources/rudder/rudder-web/src/main/bom.json target/tmp_server-frontend.json

  # cargo (Rust)
  cargo cyclonedx --all --format json --manifest-path rudder/policies/rudderc/Cargo.toml
  mv rudder/policies/rudderc/rudderc.cdx.json target/tmp_server-rudderc.json

  # Aggregate everything
  cyclonedx merge --input-files target/tmp_server-*.json --output-file "target/rudder-server-${VERSION}.cdx.json"
  rm -f target/tmp_*
}

relay() {
  checkout rudder "${VERSION}"
  cargo cyclonedx --all --format json --manifest-path rudder/relay/sources/relayd/Cargo.toml
  mv rudder/relay/sources/relayd/rudder-relayd.cdx.json "target/rudder-relay-${VERSION}.cdx.json"
}

agent_windows() {
  if [ "${PRIVATE}" = "true" ]; then
    checkout rudder-agent-windows "${VERSION}"
    # dotnet (F#)
    dotnet CycloneDX --exclude-dev --json rudder-agent-windows/common/initial-policy/ncf/rudderLib/rudderLib.sln --filename "target/rudder-agent-windows-${VERSION}.cdx.json"
  fi
}

plugin_repo() {
  repo="$1"
  checkout "${repo}" "master"
  tags=$(git -C "${repo}" tag | grep -E "^[a-z-]+-${VERSION}-.*")
  for tag in ${tags}; do
    checkout "${repo}" "${tag}"
    plugin=$(echo "${tag}" | sed -E "s/^([a-z-]+)-${VERSION}-.*\$/\1/")
    cd "${repo}/${plugin}"
    # Only work on webapp plugins
    if [ -f pom-template.xml ]; then
      ## [ -f main-build.conf ] && echo '.' || echo '..'
      pwd
      make MAIN_BUILD="../main-build.conf" generate-pom
      mvn "org.cyclonedx:cyclonedx-maven-plugin:${MAVEN_CYCLONEDX_VER}:makeAggregateBom" --file pom.xml
      mv target/bom.json "../../target/rudder-${tag}.cdx.json"
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
}


rm -rf target rudder-sbom-*
mkdir target
server
relay
plugin_repo "rudder-plugins"
if [ "${PRIVATE}" = "true" ]; then
  plugin_repo "rudder-plugins-private"
  agent_windows
fi
mv target "rudder-sbom-${VERSION}"
tar -cf "rudder-sbom-${VERSION}.tar.xz" "rudder-sbom-${VERSION}"
