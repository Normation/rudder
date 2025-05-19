# rust is the latest debian image with latest rust preinstalled
FROM rust

# Is there a reason to lock a specific version ?
RUN cargo install -f typos-cli
RUN apt-get update && apt-get install -y shellcheck pylint curl

RUN mv /usr/local/bin/rsync /usr/local/bin/rsync.real
COPY ci/wrap_rsync.sh /usr/local/bin/rsync
RUN chmod +x /usr/local/bin/rsync 
