FROM node:16-bullseye
LABEL ci=rudder/api-doc/Dockerfile
ENV NODE_ENV=production

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID ;\
    npm install --global @redocly/cli@1.0.0-beta.111 redoc-cli@0.13.20 ;\
    apt-get update && apt-get install -y rsync python3-yaml && rm -rf /var/lib/apt/lists/*
