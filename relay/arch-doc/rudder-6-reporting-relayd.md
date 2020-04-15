# Rudder 6 - reporting and relayd architecture

*Notice*: This document was written while developing Rudder 6.0, some parts may be outdated.

## Resources

- Talk at CfgMgmtCamp 2020: [slides](https://speakerdeck.com/rudder/designing-the-future-of-agent-server-communication-in-rudder), [video](https://www.youtube.com/watch?v=l-ztfw_OIow)

## Overview

This aims at providing a new communication protocol between Rudder agent and its policy server, and an implementation for both agents and servers, allowing for several types of information to be transferred securely. This first version targets particularly the agent log transfer.

It is the first step of a larger plan to provide:

- A better security for Rudder communications

- Extend performance to scale up to 50k nodes

- Continuity and Reactivity, and better Compliance

Changes are split into different areas:

- A new `relayd` component
- An improved security model
- A new reporting format (agent+server+new data)
- A new transport protocol for reporting (HTTPS)

## Context

### Components

Rudder is primarily made of:

- Agents installed on all managed systems, called nodes. Their behavior is simple : every N minutes (5 by default), they will:
  
  - Update their target configuration from their reference server
  
  - Apply the configuration locally
  
  - Send feedback to the server about the actual state of the system
  
  - Additionally, they will send a full software and hardware inventory every night at a random time.

- A central server, called the root server, that allows defining the target configuration, making it available to the nodes and exploit feedback from the agents to expose compliance information

- Relays servers, that are proxies between nodes and a central server, centralizing network streams and caching content in case of connection trouble

This document describes changes in the way nodes communicate their feedback after executing the agent, and in the implementation of relay features.

### Communication protocols

- CFEngine protocol (specific protocol inside TLS) for policy update and remote run on Unix (tcp/5309)

- HTTP(S) for daily inventory update, and policy update on Windows (tcp/443 or 80 for AIX agents)

- Syslog (UDP/TCP) for reporting on Unix (tcp/514 or udp/514)

### 5.0 issues

Pre-6.0 implementation has several issues:

- Inconsistent and unsatisfying security model

- Invasive changes on Unix systems with syslog usage

- Complex administration and maintenance for users

- Limited performance and slow propagation (no remote run ability on Windows, requires an open port on Unix)

- Limited evolution to improve precision of reported information due to the way logs are produced and treated

## Goals

- Unified and consistent security model

- Reduced number of communication channels

- Reactive node behavior for a more continuous configuration

- No impact on node's system (no root access needed, no mangling with system services, etc.)

- Easier debugging

## Non-goals

- Replace CFEngine protocol. Even though the policy update part could be quite easily replaced by HTTPS, it is still required for advanced recursive copies from the agents.

## Requirements

We want:

* Our agent has to stay compatible with old and diverse operating systems, and be able to expand to new (as in "new in Rudder") ones (FreeBSD, Solaris, etc.)
* The relay can target recent Linux distributions only but needs to be able to run on light hardware (embedded, etc.)
* Even if we introduce connected features, we need to stay fully compatible with unconnected use cases, without persistent connections between nodes and policy servers, and between policy servers themselves
* We need to target support of thousands of nodes by relay, and tens of thousands by root server. A 50k nodes infrastructure would be a good target
* Our security model cannot rely on user-managed CA and certificates as Rudder is often use to configure these, and needs to keep running when they are broken

---

## Reporting format

### From reports to runlogs

The reporting in Rudder is based on _reports_ which are log lines describing the state of one component of the system. They are produced by the agent, sent to the root server which parses them and use them for compliance computation and traceability.

They look like:

```bash
2019-05-14T08:58:13+00:00 R: @@OpenSSH server@@result_success@@32377fd7-02fd-43d0-aab7-28460a91347b@@c844d80c-8f5d-4b93-83d6-a3a65ae8a6eb@@0@@SSH configuration@@None@@2018-08-24 15:55:01+00:00##e745a140-40bc-4b86-b6dc-084488fc906b@#The OpenSSH server configuration was correct
```

Each report contains the full context needed to make use of it.

They are inserted into the database by rsyslog, into a table directly mapped to the reports structure:

```sql
CREATE TABLE RudderSysEvents (
  id                 bigint PRIMARY KEY default nextval('serial')
, executionDate      timestamp with time zone NOT NULL
, nodeId             text NOT NULL CHECK (nodeId <> '')
, directiveId        text NOT NULL CHECK (directiveId <> '')
, ruleId             text NOT NULL CHECK (ruleId <> '')
, serial             integer NOT NULL
, component          text NOT NULL CHECK (component <> '')
, keyValue           text
, executionTimeStamp timestamp with time zone NOT NULL
, eventType          text
, policy             text
, msg                text
);
```

There are two specific control logs, allowing to delimitate and contextualize a run: a start and end flag (resp. `StartRun` and `EndRun`). The application starts considering a run once the `EndRun` has arrived.

This model has several drawbacks:

- Performance
  
  - Wasted space due to context repetition (date, node id, run id, etc.)
  
  - Expensive computing to put reports back together into a runlog, to be able to use them for compliance
  
  - Prevents efficient compression

- We take no advantage of reports streaming as we wait for the last one to be able to use the data.

#### Runlog format specification

We are moving from a streaming log model to a one-shot report, allowing compression and signature, without losing any previous feature.

Reports are plain text MIME-objects signed using a sha-256 S/MIME signature. The content of the report is the raw output of the `cf-agent` command.

### From reports to reports+logs

#### 5.0 situation

On Unix, the raw agent output contains:

- on `stderr`: errors from CFEngine

- on `stdout`:
  
  - Rudder reports
  
  - Normal reports
  
  - CFEngine logs (info, warn, verbose or debug)

The agent has different verbosity level:

- quiet
  
  - `result_repaired`, `audit_error` or `result_error` Rudder reports
  
  - CFEngine errors

- (default)
  
  - all `result_*` Rudder reports

- info
  
  - all Rudder reports (including `log_*`)
  
  - CFEngine `warn` and `info` logs (containing deltas, executed commands and their output, etc.)
  
  - Simple reports in `info::`

- verbose
  
  - CFEngine verbose logs
  
  - Simple reports in `debug::`

- debug
  
  - CFEngine debug logs

Only the Rudder reports are sent to the server (through syslog).

#### Problems

It is almost never possible to understand the cause of a compliance problem from the Rudder web interface. Rudder `log_*` reports might help but they are not displayed and the user has to look for them in the technical logs.

Understanding a problem always means login on the node but:

- Local logs only contain what is sent to syslog, so they are generally of no help to understand a past problem

- The user has to start the agent with the appropriate verbosity level to see actually useful messages.

#### Goals

Give the maximal amount of relevant feedback to the user, up to the web compliance view:

- Which actions have been done (executed commands, etc.)

- What was the outcome of these actions (error messages, return codes, etc.)

- What is the difference between expected and actual states (permissions, file content, etc.) in case of error or non-compliance

#### Proposition

We can greatly improve user experience by using data we already have available but do not use efficiently.

##### Agent side

- Use `info` as the lowest verbosity level when starting the agent (in CLI ou by cf-execd), and filter it for CLI output to match user-requested verbosity level

- Use the standard output instead of the reports mechanism, to be able to capture all messages

- Use stdout for errors too, like upstream changed recently, to have proper message ordering and easier output handling

##### Relayd side

- Parse the output of the complete output of the agent, including message over multiple lines, incomplete reports, etc.

- Attach messages preceding a report to the said report (it will almost always be correct), by generating standard `log_*` reports with the right metadata and log level.

##### Webapp side

- Make the log messages attached to a report visible in the interface

#### Challenges

- Increase in reports size (especially in some corner cases like old package methods that throw a log of info messages), so in bandwidth usage and reports database size (which is already often a problem)
  
  - Compression if reports will mitigate the bandwidth problem
  
  - We should limit the size of a report to a reasonable value
  
  - We can disable reports archiving by default, as it is generally useless

- Log contextualization will often be wrong:
  
  - It will be correct for techniques from the technique editor
  - It will _sometimes_ be correct in system techniques
  - It will be mostly wrong in built-in technique, because the reporting is usually made at the very end

- Diffs will not be complete, especially for file diffs which are not currently supported by the agent
  
  - This could be a future development once the infrastructure is in place

## Reporting protocol

#### 5.0 situation

Rudder uses syslog for reporting. The underlying protocol (UDP or TCP) is configurable at the root server level. rsyslog is used on policy servers to collect logs and on root server to parse and insert them into postgresql.

This has several advantages:

* It was very easy to start reporting in first releases, because all tooling was already available on Unix systems
* Performance (except for the postgresql output module in old versions)
* Well-known by sysadmins (both protocol and services like rsyslog, syslog-ng, etc.)

Problems include:

* TLS set up for syslog is hard, and if not set-up, all reports are sent in plain-text, while they often contain sensitive information. This prevents running Rudder over an untrusted network.
* There is no source authentication, so anyone in the network can generate fake reports
* We need to install _nxlog_ along with our Windows agent to provide syslog support
* Syslog problems, especially with UDP have proven very hard to debug
* When using TCP, it is easy to overflow the syslog infrastructure and affect global system's network stack
* Configuring the local syslog daemon requires root access
* Rudder configuration sometimes conflicts with user configuration
* Reporting uses a totally different mechanism than other communication, which makes it a installation and maintenance burden

#### Requirements

The replacement must:

* Have full encryption (using TLS 1.2+)
* Use embedded Rudder tools, and do not require root access
* Run everywhere (embedded hardware, AIX 5.3, RHEL 3, etc.)
* Allow non-connected modes
* Be able to report bigger content (full file content, etc.)
* Be extensible 

#### Considered solutions

We considered various solutions

- message queues: adds operation complexity, can be hard to understand and debug for users

- gRPC: not good for agent communication: async + big files

### From syslog to https (WebDAV)

Inventories are sent to the server using WebDAV.
We decided to keep it this way and to use
the same configuration for sending reports.

* Low impact
* Fallback mode for limited agents
* Allows using stored files as on-disk queue

The processing program will read files written by apache
(like we do for inventories).

This will allow keeping compatibility for old agents,
and avoids having to change server configuration.

### From https PUT to an https API

* connected mode?
* http/2?
* serialized messages?
* client part in Rust?

This may be done in a second time. Requires a storing format, etc.

Should improve performance significantly, and allow richer reports.

## New `relayd` component

### Why Rust?

We considered several languages but finally settled on Rust.

One of the firsts were Scala which is already used in the main application. The main issue is that we want relayd to be able to run on light hardware, sometimes embedded, with low resource consumption, contrary to the root server.

For performance reasons (and for deployment ease) as we want our relay to be able to run on very light devices (embedded/IoT targets), we want to avoid interpreted languages.

Possible targets now included Go, C, C++, Rust.

Our previous projects made us prefer static typing, and a language that focuses on program safety, so we decided to start our proof of concept using Rust, and then decided to use it for the final version.

The Rust choice is still not perfect, as we won't be able to use it in the agent for now as we need AIX support (which [is not there yet](https://forge.rust-lang.org/platform-support.html)), and Rust is hence limited to
our server components.

### Dependencies, packaging and system integration

The new component has few system dependencies :

- `libpq` (postgresql client libraries)

- `openssl` (for https and S/MIME support)

- `zlib` for gzip compression

As we only support recent operating systems, we can rely on the presence of systemd. The service is only provided as a system unit, and rely on it for output redirection and daemonization.

### Relay API

Previously, some relays features were achieved by a small API called relay-api, implemented in Python using flask and wsgi. This API is used for:

- Triggering remote agent execution from Rudder servers

- Allow file sharing between nodes (which the `Shared file to/from` methods)

- Allow shared-files download from Windows agents

Decided to move these features into relayd for several reasons

- Easier maintenance. Python has proven hard to package correctly on multiple platforms and maintain in the long run (virtualenv on old platforms, python 3 and so on.). Refactoring have been painful.

- This code was not-so-good and required a deep rework to be usable for core features

- Better performance

- Simplicity: limit the number of different components to manage

### Monitoring and Observability

Development of relayd is done with observability, tracing and monitoring in mind. The API provides endpoints for:

- Status monitoring, with the state of the relayd sub-systems

- Statistics

All logging is made using `tokio/tracing` which allows flexible contextualization of events and through context spans. This will allow easy visualization of the program's behavior.

### Inventory forwarding

#### 5.0 situation

Inventories are forwarded from the relay by the agent, which runs every 5 minutes. This adds an annoying delay to inventories, especially during provisioning.

#### Proposed solution

Leverage the inotify-based watcher used for reports in relayd to forward inventories too.

### Security and sandboxing

We tried to follow current best practices in terms of process confinement, by providing seccomp, namespace and SELinux configurations and allowing easy contenerization, besides running as a dedicated system user with minimal permissions.

We will continue applying these kind of configuration to other Rudder components in future releases.

#### SELinux

Relay is the first Rudder service to get a dedicated SELinux policy and run in a dedicated context. This policy ensures relayd only has access to files it requires (in read-only to its configuration and data files, in read-write mode to its working directories, i.e. reports and inventories).

#### Sandboxing

We used [simple sandboxing features](https://github.com/Normation/rudder/blob/master/relay/sources/systemd/rudder-relayd-hardening) of systemd:

```ini
ProtectSystem=strict
ReadWritePaths=/var/rudder/reports /var/rudder/inventories
PrivateTmp=True
```

This prevents the process from writing outside of its working directories (reports and inventories) and makes it use a private tmp folder. It is only applied on recent enough systemd version (RHEL8, Debian 10, etc.) because these features were not present before. We considered adding a syscall filter too (with ``SystemCallFilter``), but given the diversity of platforms we support and features of the relay daemon, it would not be easy to cover all possible cases and could easily lead to bugs. It may be added in the future.

## Security model

### 5.0 situation

#### Inventories

To be completed.

#### Unix agent policy copy

To be completed.

#### Windows agent policy copy

To be completed.

#### Reporting

All nodes currently have:

- An RSA 4046 key pair (1024 bits for agents installed before ...)

- A certificate based on this key for policy update, only on Windows

All inventories contain the public key (on Unix) or certificate (on Windows), and they are signed using it (signature uses a detached file with a custom format). On the node has been accepted, new inventories need to be signed with the same key to be accepted by the server. We want to leverage this security model for reporting, but with a more standard signature format (now we have a consistent tool set on all nodes, after embedding curl and openssl when needed).

We decided to:

- Add a certificate for the existing key on Unix nodes, and replace the public key by the certificate in the inventory. This makes our inventories and agent credentials homogeneous and allows future extensions
(as certificates are more flexible).

### TLS 1.2+

We [are also be able](https://issues.rudder.io/issues/14786), now we control the tooling with embedded dependencies when needed, to enforce the usage of TLS 1.2+ everywhere in Rudder communications (except for nodes still using legacy syslog reporting of course).

### Keys

Each agent has a key-pair for authentication. Until [Rudder 4.3](https://issues.rudder.io/issues/6253), keys were RSA 2048bit, they are now RSA 4096bit for which we added support in CFEngine.

Using more modern algorithms (such as ed25519) would require compatibility of the whole chain. In particular, CFEngine only supports RSA for now. However, even if not small or fast, RSA >= 2048 is still considered safe as of 2019.

Agent private keys are encrypted (AES256 on Windows, DES on Unix) with a static password (`Cfengine passphrase`) for compatibility (but this obviously provides no security).

Starting from 4.0 on Windows and 6.0 on Linux, agents also have a self-signed sha256 certificate for their key pair, containing their unique id in the subject. It can be used for authentication, signing or encrypting files (`keyUsage: digitalSignature, dataEncipherment, keyEncipherment, keyAgreement`).

Agent certificate is created with:

```bash
openssl req -new -sha256 -key ${CFE_DIR}/ppkeys/localhost.priv -out /opt/rudder/etc/ssl/agent.cert -passin "pass:Cfengine passphrase" -x509 -days 3650 -extensions agent_cert -config /opt/rudder/etc/ssl/openssl-agent.cnf -subj "/UID=$(cat "${UUID_FILE}")"
```

These are used for:

* Authentication for the CFEngine protocol (doesn't use the certificate, TOFU behavior)
* Authentication for Windows policies download (client certificate in HTTPS)
* Signing inventories (without using the certificate)
* Signing reports (using the certificate)

Server certificate, used only for HTTPS:

```bash
SUBJALTNAME=DNS:$(hostname --fqdn) openssl req -new -x509 -newkey rsa:2048 -subj "/C=FR/ST=France/L=Paris/CN=$(hostname --fqdn)/emailAddress=root@$(hostname --fqdn)/" -keyout /opt/rudder/etc/ssl/rudder.key -out /opt/rudder/etc/ssl/rudder.crt -days 1460 -nodes -sha256 -config /opt/rudder/etc/ssl/openssl.cnf -extensions server_cert
```

It is used for all HTTPS traffic (used by Apache httpd), only on server side.

To sum things up:

* All keys are RSA >= 2048 bit
* All hashes are SHA2 (sha256 or sha512)
* Embedded OpenSSL is currently 1.1.1

### Server certificate validation

An option was added in the global settings to allow validating the server certificate on HTTP calls made from the node
to its policy server (reports,inventory, shared-files). It allows ensuring the agent is not sending sensitive data
to a malicious server.

This mechanism requires an existing PKI, and is separate from Rudder's general security model.

Future versions should make the different mechanism converge around a common basis.

### Reports signature

#### Considered solutions

- The custom detached signature format currently used for inventories:
  
  - Compute a signature from the file to sign with `openssl dgst`
  
  - Store it in a detached `.sign` file containing:

```
header=rudder-signature-v1
algorithm=${HASH}
digest=${SIGNATURE}
hash_value=${HASH_VALUE}
short_pubkey=${SHORT_PUBKEY}
hostname=${HOSTNAME}
keydate=${KEYDATE}
keyid=${KEYID}
```

- GPG signature but it cannot use our current keys, and would be a lot overhead (new dependency, etc.)
- S/MIME signature
- minisign/signify but they don't support RSA (only Ed25519)

#### Chosen solution

We chose S/MIME (4.0) because:

- An embedded signature would be easier to manage
- A standard format allows easier integration
- The trust model in Rudder is closer to a PKI than a Web of trust, and we might want to move to a global CA in the future, which matches S/MIME target use cases
- We already provide openssl which can handle S/MIME signatures
- MIME offers us standardized extensibility (attachments for reports, etc.)
- Allows easily switching to encryption with the same tooling and workflow

NOTE: You may have heard about vulnerabilities affecting S/MIME (and GPG) signatures, like [EFAIL](https://efail.de/), but they mainly affect emails clients and not our use cases. The main threat is exfiltration of encrypted data by modifying the ciphertext to insert backchannels (and break message signature to make to skip integrity control). We are safe from these issues as:

* We only use signature for now, and will always be able to enforce the presence of a (valid) signature in every runlog

* We follow the latest (post-EFAIL) S/MIME 4.0 standard and do not handle compatibility with previous versions, which means if we ever introduce encryption, it would use an algorithm with authenticated encryption

The signature is done on the agent using:

```bash
openssl smime -sign -text -nocerts -md sha256 -signer "${CERT}" -inkey "${PRIVKEY}" -passin "pass:${PASSPHRASE}" -in "${tmp_file}" -out "${tmp_file}.signed"
```

We are using the following parameters:

- `-text` to set a plain text MIME-type (reports are all plain text)
- `-nocerts` to disable certificate embedding in our signed file, which makes the signed files much lighter. They are not needed as the server already knows them thanks to the inventory.
- `-inkey` is the agent private key (`localhost.priv`)
- `-passin` allows passing the password for the private key on Unix agents
- `-signer` is the certificate generated from the agent private key (`localhost.cert`)
- `-md sha256` use SHA256 for signed digest

### Inventory signatures

As described in the previous section, inventory signature uses a custom format. It is unpractical, inconsistent and risky, and we will consider moving to S/MIME 4.0 in a future release.

### Reports and inventory encryption

We could decide to move to reports and inventory end to end encryption, it would only require to distribute server's certificate everywhere (which can be done through the policies, or through a proper CA). This would for example allow using untrusted relays (but only after a proper key exchange through a verified inventory).

---

## Transition

Rudder 6.0 will be the first version shipping a part of the changes described in this document, see the [time-line](#Time-line) for more information.

The new reporting protocol will only be available starting from 6.0 agents, meaning it won't be available for previously installed agents before upgrade to 6.0. Such agents will continue to use syslog reporting.

Both new and old reporting modes will be available to keep a safe backup in case problems occur.

Can be changed at any time, with by-node overrides

We will also add a locked-down mode to disable syslog once all nodes are compatible with the new reporting to ensure all reports are signed.

## Time-line

### 2018 Q2 : Beginning of the project

- Study of possible options

- Development of various proofs of concept

### 2019 Q4: Rudder 6.0

- Add the new reporting mode

- Improve precision of reporting messages

- Enforce TLS 1.2+ for every communication in Rudder

- Move relay-api implementation to relayd

- Ability to validate server certificate in HTTPS connections using existing PKI

### 2020: Rudder 6.1

- Add support for HTTP reporting on Windows agents
- Add HTTPS reporting support in "changes only" reporting mode
- Continue sandboxing services

### 2020-2021: Rudder 7.0+

- Remove syslog reporting mode
- Implement remote-run for Windows nodes
- Add delta information for most components, including files to repaired and audit components

### Future

- New runlog serialization in reporting, standard, more compact and properly organized (maybe binary, maybe with a schema, but factorized compared to what we have)
- Connected mode
- End-to-end encryption
