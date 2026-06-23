FROM debian:13
LABEL ci=rudder/docs/cheatsheet/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

RUN apt-get update && apt-get install -y --no-install-recommends curl xz-utils ca-certificates make rsync openssh-client unzip \
    fontconfig fonts-lato fonts-jetbrains-mono \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://repository.rudder.io/build-dependencies/fonts/Raleway.zip -o /tmp/raleway.zip \
    && mkdir -p /usr/local/share/fonts/raleway \
    && unzip -o /tmp/raleway.zip -d /usr/local/share/fonts/raleway \
    && rm /tmp/raleway.zip \
    && fc-cache -fv

ARG TYPST_VERSION=0.15.0
RUN curl -fsSL https://github.com/typst/typst/releases/download/v${TYPST_VERSION}/typst-x86_64-unknown-linux-musl.tar.xz \
    | tar -xJ --strip-components=1 -C /usr/local/bin typst-x86_64-unknown-linux-musl/typst
