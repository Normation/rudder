FROM node:18-bullseye
LABEL ci=rudder/api-doc/Dockerfile
ENV NODE_ENV=production

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID && \
    apt-get update && apt-get install -y rsync python3-yaml && rm -rf /var/lib/apt/lists/*
