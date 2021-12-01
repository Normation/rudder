# Rudder 7 - HTTP communications

*Notice*: This document was written during Rudder 7.0 development, some parts may be outdated.

## Overview

This document describes the changes in network communication security for 7.0.

The goal is to make the security mechanisms more consistent, and to properly
authenticate all communications by default, especially HTTPS communications. as syslog is dropped in 7.0, and CFEngine provide its own security mechanisms, described below.

## Rudder Unix agent communications (CFEngine-based)

*Note*: The security mechanism evolved in 6.0.10/6.1.6 to address a security vulnerability. This description includes these changes.

This model is based on CFEngine model but extends it a bit to match some of our use cases.

Each agent has an id to identify it (a UUID, except for root) and an RSA key pair (4096 bit by default, stored in `/var/rudder/cfengine-community/ppkeys/localhost.{priv,pub}`). It used both by the agent and the server programs. All communications use TLS (1.2+), authenticated by these keys.
These are generated during agent installation.

CFEngine provides two layers of restrictions:

* a list of trusted public keys
* public-key based ACLs for specific resources

The list of trusted keys is controlled either manually or by automatically trusting keys from a specific list of hosts. They are stored in `/var/rudder/cfengine-community/ppkeys/MD5-${KEY_HASH}.pub`.

In Rudder we cannot rely on this list to limit access to policy servers as we need to distribute initial policies, so we need to allow anyone (in the allowed networks) to be automatically trusted by the server.

However, on simple agents, we rely on this mechanism to only trust the first server we connect to, and stop trusting new servers after the first correct policy download. When a server public key hash is provisioned at installation, automatic trust is even totally disabled. This prevents connection to unknown policy servers. This works as an additional protection layer, with the ACL system described below.

As it cannot work on policy servers, we rely heavily on public-key based ACLs.

We as them in three different contexts:

* On all agents (either simple agent or policy server) to control which server we can download policies or shared-files if policies from. This uses the (`copyfrom_restrict_keys` keyword.
* On all agents (either simple agent or policy server) to control which server are allowed to trigger a remote agent execution.
* On policy server, policy downloads uses different folders for each nodes, and
only allow the target node to download its policies.

The only actions available on policy server without explicit authorization is the initial policies and configuration library ("ncf") downloads.

In case of policy reset (either in case of policy corruption or manual `rudder agent reset`) we need to keep configured trust. To do so, in addition to the policies, the policy server hash is stored outside of the `inputs`, in `/var/rudder/cfengine-community/ppkeys/policy_server_hash` (containing the md5 hash of the key used by CFEngine). If this file is present, policy download will only be possible from the policy server it identifies.

The workflow from the beginning:

* an agent is installed on a new node, it creates its key pair automatically (plus a self-signed X.509 certificate based on this key)
* the user configures the policy server's address (or hostname). Optionally, the user can provide the server's public key.
* the node then downloads its initial policies from the configured server. It automatically
  trust the server's key if no key was pinned at installation.
* the initial policies will create and send an inventory containing the agent certificate (containing the public key)
* the new node appears on the server, and once accepted, its policies are generated and made available on the policy server, with
  a key ACL based on the key provided in the inventory.

All following inventories need to be signed with the node's key.

## Existing HTTPS security

Currently we have:

* Agent client authentication using the key for policy and shared-files download for the Windows agent.
* For server authentication, two modes: unverified (default) and verified using user provided certificates for HTTP servers (relays and root).

## Changes

We want to generalize the pinning used for CFEngine for all HTTPS communications and use the same authentication mechanisms (agent key + certificate). We want to make it TOFU by default, but make it possible to pre-establish trust too.

This means replacing the current HTTPS certificates, and enforce using Rudder keys and certificates. Then we can verify all HTTPS connections to the servers.

This means:

* Replace server HTTPS certificate by the agent certificate. This means they won't be replaceable by user-provided certificates
  like in 6.X, and that we won't support custom reverse proxies.
* For the web/API part, it will be possible to configure a different virtual host with a proper certificates.
* Add a new agent shell/powershell library to handle communications. It will handle key pinning.
* Make HTTP post configurable (but keep 443 by default), and allow configuring a proxy for Linux systems for
  more flexible network requirements.
* Add an `agent.conf` file extending uuid/policy server configuration with other connection information: path to key hash, port and proxy to use.
  We will keep it minimal, and it will only contain what is necessary to connect to the server a first time.

### Passphrase on agent private key

Following a behavior change in CFEngine 3.18, we decided to also remove the passphrase (which was hardcoded everywhere)
from the agent private key.

This allows using it directly in other programs configuration (like httpd) without trouble, without significant
added risk as the passphrase was publicly known.

### On the agent

We use the public key pinning feature of curl. This feature is close to [HPKP](https://developer.mozilla.org/en-US/docs/Web/HTTP/Public_Key_Pinning), now deprecated and removed from browser.

We will change the curl calls to add a `--pinnedpubkey <hashes>` option using the stored public key hash.

The required hash (a base64-encoded sha256 hash of the server public key in DER format) will be stored in a file, similar to what is already done for CFEngine in `ppkeys/policy_server_hash`.

This hash can be computed with:

```bash
# from the private key
openssl rsa -in localhost.priv -outform der -pubout | openssl dgst -sha256 -binary | openssl enc -base64
# or from the certificate
openssl x509 -in agent.cert -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
```

*Note*: This does not work with *localhost.pub* as it is a raw RSA key and not in X.509 wrapper. Without this we could have used the path to `localhost.pub` directly as `--pinedpubkey` parameter.

### On the relay

We can't easily use public key pinning for `relayd` as it is not exposed by the Rust http stack in `tokio`.

Today our HTTPS stack is:

```
reqwest -> hyper-tls -> tokio-native-tls -> native-tls -> openssl -> (openssl lib)
```

Ideally pinning should be checked at connection and not once connected, so it would require a new parameter in https://docs.rs/native-tls/0.2.7/native_tls/struct.TlsConnectorBuilder.html (plus the actual public key comparison implementation). Then we need to make it available higher in the stack, in `reqwest` client builder.

We will use X.509 certificate validation, with some tricks to make it validate the identify with a pinned certificate.

A relay connects to:

* Its sub-relays for remote-run
* Its upstream policy server for reports and inventory forwarding and shared files forwarding.

The sub relays certificates are already available in `allnodescerts.pem` on the root server, but will need to be distributed to simple relays to allow chaining.

The policy server's certificate will be distributed as part of the policies, along with the root server's certificate in allow cases to allow future end to end file encryption. Is is enough as we can't do anything before the first policy update anyway.

`relayd` is modified to use a different HTTP client for each node it talks to, configured with:

* The target certificate as unique CA
* Disabled system root certificates
* Disabled hostname validation

This is equivalent to the public key pinning, and only check if the remote server has the right private key, matching the certificate.

However this only works since openssl 1.1.1h ([with this commit](https://github.com/openssl/openssl/commit/e2590c3a162eb118c36b09c2168164283aa099b4)), and is not available in rustls which does not permit skipping hostname validation for now. Before this, the certificate is not considered valid and fails with `depth lookup: unable to get local issuer certificate`.

As a workaround we use the vendoring feature of `rust-openssl` to embed the latest openssl in `relayd`.

#### Configuration reload

As policy server and sub relays certificates can change (for example when a relay is added/removed), we want to avoid to restart `relayd`,
which would close all open connections (like running remote-runs, API calls which would fail, etc.) In order to avoid this, we
will reload only the modified HTTP clients without restarting the whole service.

This only applies when the "cert_pinning" peer authentication mechanism is used (i.e. with a 7.0 server). In this case,
when creating the clients, we also store the pinned certificate along with it. Then when a configuration and data files reload
is triggered, when can read the certificates on the filesystem and compare with what is stored with the clients.
If it is up-to-date, the client is kept intact. If not, of if it a new relay, a new client will be created, and will
replace the previous one.

#### Port and proxy change

We add new entries to the configuration file for these parameters, and pass them to the HTTP clients.

#### Service reload/restart

TODO

### `agent.conf`

A new `/opt/rudder/etc/agent.conf` (in toml like our other recent configuration files, but staying simple so that it can be parsed with grep) will allow configuring everything linked to agent-server communication, the rest being part of the policies.

```toml
# This file configures the way the Rudder agent connects to its server

# Public key hash used by our policy server
#server_pubkey_hash = "C:\\Program Files\\Rudder\\var\\ssl\policy_server_hash"
#server_pubkey_hash = "/var/rudder/lib/ssl/policy_server_hash"

# Port used for HTTP-like communication
#https_port = 443

# Proxy configuration for HTTP-like communication
#proxy = "https://user:password@proxy.example.com"
```
