ARG JDK_VERSION=11
FROM maven:3-openjdk-${JDK_VERSION}

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

# Install elm
RUN curl -L -o elm.gz "https://github.com/elm/compiler/releases/download/0.19.1/binary-for-linux-64-bit.gz"
RUN gzip -d elm.gz
RUN chmod +x elm
RUN mv elm /usr/local/bin/elm-0.19.1
