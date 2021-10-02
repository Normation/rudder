FROM rust:1.55.0-bullseye

RUN apt-get update && apt-get install openssl-dev

