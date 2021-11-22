FROM node:16-bullseye
ENV NODE_ENV=production

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

RUN npm install --global @redocly/openapi-cli@1.0.0-beta.69 redoc-cli@0.13.0
RUN apt-get update && apt-get install -y rsync python3-yaml && rm -rf /var/lib/apt/lists/*
