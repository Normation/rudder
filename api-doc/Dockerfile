FROM node:24-trixie
LABEL ci=rudder/api-doc/Dockerfile
ENV NODE_ENV=production

RUN apt-get update && apt-get install -y rsync python3-yaml && rm -rf /var/lib/apt/lists/*
