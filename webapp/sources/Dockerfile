ARG JDK_VERSION=11
FROM maven:3-eclipse-temurin-${JDK_VERSION}
LABEL ci=rudder/webapp/sources/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

# Install elm
RUN curl -L -o elm.gz "https://github.com/elm/compiler/releases/download/0.19.1/binary-for-linux-64-bit.gz" ;\
    gzip -d elm.gz ;\
    chmod +x elm ;\
    mv elm /usr/local/bin/elm-0.19.1 ;\
    apt-get update && apt-get install -y npm ;\
    npm install terser@5.13.1 -g
