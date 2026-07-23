ARG JDK_VERSION=17
FROM maven:3-eclipse-temurin-${JDK_VERSION}
LABEL ci=rudder/webapp/sources/Dockerfile

ARG NODE_VERSION=24
ARG TRIVY_VERSION=0.72.0

ARG USER_ID=1000

RUN apt-get update && apt-get install -y curl gpg

# We need a recent node
RUN curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /usr/share/keyrings/nodesource.gpg
RUN echo "deb [arch=amd64 signed-by=/usr/share/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_VERSION}.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list > /dev/null

RUN apt-get update && apt-get install -y nodejs

# --- Trivy ---
RUN curl -sSL -o /tmp/trivy.tar.gz \
      "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz" \
    && tar -xzf /tmp/trivy.tar.gz -C /usr/local/bin trivy \
    && rm /tmp/trivy.tar.gz

