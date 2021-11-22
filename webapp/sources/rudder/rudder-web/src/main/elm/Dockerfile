FROM node:16-bullseye
ENV NODE_ENV=production

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

RUN npm install --global elm elm-test
RUN ln -s /usr/local/bin/elm /usr/local/bin/elm-0.19.1
