ARG JDK_VERSION=17
FROM maven:3-eclipse-temurin-${JDK_VERSION}

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

RUN apt-get update && apt-get install -y npm
