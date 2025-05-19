# rust is the latest debian image with latest rust preinstalled
FROM rust

# Is there a reason to lock a specific version ?
RUN cargo install -f typos-cli
RUN apt-get update && apt-get install -y shellcheck pylint curl

RUN mv /usr/bin/rsync /usr/bin/rsync.real
COPY ci/wrap_rsync.sh /usr/local/bin/rsync 
