FROM node:18-bullseye
LABEL ci=rudder/api-doc/Dockerfile
ENV NODE_ENV=production

RUN apt-get update && apt-get install -y rsync python3-yaml curl && rm -rf /var/lib/apt/lists/*

RUN mv /usr/bin/rsync /usr/bin/rsync.real
COPY ci/wrap_rsync.sh /usr/local/bin/rsync 
RUN chmod +x /usr/local/bin/rsync 