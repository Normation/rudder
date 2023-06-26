ARG JDK_VERSION=11
FROM maven:3-eclipse-temurin-${JDK_VERSION}
LABEL ci=rudder/webapp/sources/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID && \
    apt-get update && apt-get install -y npm
