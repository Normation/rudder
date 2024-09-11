ARG JDK_VERSION=17
FROM maven:3-eclipse-temurin-${JDK_VERSION}
LABEL ci=rudder/webapp/sources/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .

RUN ./user.sh $USER_ID && \
    apt-get update && apt-get install -y curl gpg

# We need a recent node
RUN curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /usr/share/keyrings/nodesource.gpg
RUN echo "deb [arch=amd64 signed-by=/usr/share/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list > /dev/null

RUN apt-get update && apt-get install -y nodejs

USER jenkins
