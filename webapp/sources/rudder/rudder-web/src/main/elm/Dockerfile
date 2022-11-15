FROM node:16-bullseye
ENV NODE_ENV=production

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

RUN npm install --global elm@0.19.1-5 elm-test@0.19.1-revision9 terser
RUN ln -s /usr/local/bin/elm /usr/local/bin/elm-0.19.1
