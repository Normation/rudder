FROM node:18-bullseye
ENV NODE_ENV=production

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID
