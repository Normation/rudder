# Rudder 7 - HTTP communications

*Notice*: This document was written during Rudder 7 development, some parts may be outdated.

## Overview

This document describes the changes in network communication security for 7.X.

The goal is to make the security mechanisms more consistent and to properly
authenticate all communications by default, especially HTTPS communications, as syslog is dropped in 7.0 and CFEngine provides its own security mechanisms, described below.

## Rudder Unix agent communications (CFEngine-based)

*Note*: The security mechanism evolved in 6.0.10/6.1.6 to address a security vulnerability. This description includes these changes.

This model is based on CFEngine model but extends it a bit to match some of our use cases.
Each agent has an id to identify it (a UUID, except for root) and an RSA key pair (4096 bit by default, stored in `/var/rudder/cfengine-community/ppkeys/localhost.{priv,pub}`). It is used both by the agent and the server programs. All communications use TLS (1.2+), authenticated by these keys.
These are generated during agent post-installation with the `cf-key` command.

*Note*: TLS authentication in CFEngine uses an unusual on-the-fly certificate generation from the keypair.

Vanilla CFEngine provides two layers of restrictions:

* a list of trusted public keys
* public-key based ACLs for specific resources

The list of trusted keys is controlled either manually or by automatically trusting keys from a specific list of hosts. They are stored in `/var/rudder/cfengine-community/ppkeys/MD5-${KEY_HASH}.pub`.

In Rudder, we cannot rely on this list to limit access to policy servers as we need to distribute initial policies, so we need to allow anyone (in the allowed networks) to be automatically trusted by the server.

However, on simple agents, we rely on this mechanism to only trust the first server we connect to, and stop trusting new servers after the first correct policy download. When a server public key hash is provisioned at installation, automatic trust is even totally disabled. This prevents connection to unknown policy servers. This works as an additional protection layer, with the ACL system described below.

As it cannot work on policy servers, we rely heavily on public-key based ACLs.
We use them in three different contexts:

* On all agents (either simple agent or policy server) to control which server we can download policies or shared-files if policies from. This uses the (`copyfrom_restrict_keys` keyword.
* On all agents (either simple agent or policy server) to control which server are allowed to trigger a remote agent execution.
* On policy server, policy downloads uses different folders for each node, and
only allow the target node to download its policies.

The only actions available on policy server without explicit authorization is the initial policies and configuration library ("ncf") downloads.

In case of policy reset (either in case of policy corruption or manual `rudder agent reset`) we need to keep configured trust. To do so, in addition to the policies, the policy server hash is stored outside the `inputs`, in `/var/rudder/cfengine-community/ppkeys/policy_server_hash` (containing the md5 hash of the key used by CFEngine). If this file is present, policy download will only be possible from the policy server it identifies.

The workflow from the beginning:

* an agent is installed on a new node, it creates its key pair automatically (plus a self-signed X.509 certificate based on this key)
* the user configures the policy server's address (or hostname). Optionally, the user can provide the server's public key.
* the node then downloads its initial policies from the configured server. It automatically
  trusts the server's key if no key was pinned at installation.
* the initial policies will create and send an inventory containing the agent certificate (containing the public key)
* the new node appears on the server, and once accepted, its policies are generated and made available on the policy server, with
  a key ACL based on the key provided in the inventory.

All following inventories and reports need to be signed with the node's key.

## Pre-7.X HTTPS security

In 6.X we have, for node-server communication:

* Agent client authentication using the key for policy and shared-files download for the Windows agent.
* For server authentication, two modes:

  * unverified (by default)
  * verified using user provided certificates for HTTP servers (relays and root).

## Changes in 7.X

We want to generalize the pinning used for CFEngine for all HTTPS communications and use the same authentication mechanisms (agent key + certificate). We want to make it TOFU by default, but make it possible to pre-establish trust too.

This means replacing the current HTTPS certificates, and enforce using Rudder keys and certificates. Then we can verify all HTTPS connections to the servers.

This means:

* Replace server HTTPS certificate used for node-server communication by the agent certificate. This means they won't be replaceable by user-provided certificates
  like in 6.X, and that we won't support custom reverse proxies.
* For the web/API part, it will be possible to configure a different virtual host with a proper certificates.
* Add a new agent shell/powershell library to handle communications. It will handle key pinning.
* Make HTTPS port configurable (but keep 443 by default), and allow configuring a proxy for Linux systems for
  more flexible network requirements.
* Add an `agent.conf` file extending uuid/policy server configuration with other connection information: path to key hash, port and proxy to use.
  We will keep it minimal, and it will only contain what is necessary to connect to the server a first time.
* The HTTPS trust is configured from the policies content (the hash file is deployed as part of the policies). On Unix systems this means the policy protocol (i.e. CFEngine's protocol) has priority and can override HTTPS settings. This avoids potential conflicts between the protocols.

### Policies

The generated policies for each node contain:

* `inputs/certs/root.pem`: the Rudder root's certificate. It could be used at some point for end-to-end encryption of inventory or reports.
* `inputs/certs/policy-server.pem`: the node's policy server's certificate (either root or a relay). Is it used in `relayd` configuration as explained below.

The `inputs/rudder.json` file contains:

```json
{
  "HTTPS_POLICY_DISTRIBUTION_PORT":"443",
  "POLICY_SERVER_KEY":"MD5=1ec2213e08921bd3444861f7b4a60919",
  "POLICY_SERVER_KEY_HASH":"sha256//LqSz+lTXd9VN4qhpQfGagTrQJjw/msKoczOc4XddhkA="
}
```

Here:

* `POLICY_SERVER_KEY` is the hash for CFEngine ACLs
* `POLICY_SERVER_KEY_HASH` is the hash for `curl` key pinning

The CFEngine configuration is handled by the CFEngine policies, the curl
pinning from the provided hash is handled by the agent CLI.

### Passphrase on agent private key

Following a behavior change in CFEngine 3.18, we decided to also remove the passphrase (which was hardcoded everywhere)
from the agent private key.
This allows using it directly in other programs configuration (like httpd) without trouble, without significant
added risk as the passphrase was publicly known.

*Note*: It hasn't been removed on Windows nodes yet.

### HTTPS calls with `curl`

We use the public key pinning feature of curl. This feature is close to [HPKP](https://developer.mozilla.org/en-US/docs/Web/HTTP/Public_Key_Pinning), now deprecated and removed from browsers.
We change the curl calls to add a `--pinnedpubkey <hashes>` option using the stored public key hash.

The required hash (a base64-encoded sha256 hash of the server public key in DER format) will be stored in a file, similar to what is already done for CFEngine in `ppkeys/policy_server_hash`.
This hash can be computed with:

```bash
# from the private key
openssl rsa -in localhost.priv -outform der -pubout | openssl dgst -sha256 -binary | openssl enc -base64
# or from the certificate
openssl x509 -in agent.cert -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
```

*Note*: This does not work with *localhost.pub* as it is a raw RSA key and not in X.509 wrapper. Without this we could have used the path to `localhost.pub` directly as `--pinedpubkey` parameter.

The hash pinning is handled directly by the CLI script that wraps curl.
If the policies contain a hash in `rudder.json`, it is stored outside the `inputs` directory
(to survive policy resets) and passed to the curl calls.
The status of HTTPS key pinning is shown is `rudder agent info` output.

### HTTPS calls from `relayd`

We can't easily use public key pinning for `relayd` as it is not exposed by the Rust http stack in `tokio`.

Today our HTTPS stack is:

```
reqwest -> hyper-tls -> tokio-native-tls -> native-tls -> openssl -> (openssl lib)
```

Ideally pinning should be checked at connection and not once connected, so it would require a new parameter in [TlsConnectorBuilder](https://docs.rs/native-tls/0.2.7/native_tls/struct.TlsConnectorBuilder.html) (plus the actual public key comparison implementation). Then we need to make it available higher in the stack, in `reqwest` client builder.

We will use X.509 certificate validation, with some tricks to make it validate the identity with a pinned certificate.

A relay connects to:

* Its sub-relays for remote-run
* Its upstream policy server for reports and inventory forwarding and shared files forwarding.

The sub relays certificates are already available in `allnodescerts.pem` on the root server, but will need to be distributed to simple relays to allow chaining.

The policy server's certificate will be distributed as part of the policies, along with the root server's certificate in allow cases to allow future end to end file encryption. It is enough as we can't do anything before the first policy update anyway.

`relayd` is modified to use a different HTTP client for each node it talks to, configured with:

* The target certificate as unique CA
* Disabled system root certificates
* Disabled hostname validation

This is equivalent to the public key pinning, and only check if the remote server has the right private key, matching the certificate.

However, this only works since openssl 1.1.1h ([with this commit](https://github.com/openssl/openssl/commit/e2590c3a162eb118c36b09c2168164283aa099b4)), and is not available in `rustls` which does not permit skipping hostname validation for now. Before this, the certificate is not considered valid and fails with `depth lookup: unable to get local issuer certificate`.

As a workaround we use the vendoring feature of `rust-openssl` to embed the latest openssl in `relayd`.

#### Configuration reload

As policy server and sub relays certificates can change (for example when a relay is added/removed), we want to avoid to restart `relayd`,
which would close all open connections (like running remote-runs, API calls which would fail, etc.) In order to avoid this, we
will reload only the modified HTTP clients without restarting the whole service.

This only applies when the `cert_pinning` peer authentication mechanism is used (i.e. with a 7.X+ server). In this case,
when creating the clients, we also store the pinned certificate along with it. Then when a configuration and data files reload
is triggered, when can read the certificates on the filesystem and compare with what is stored with the clients.
If it is up-to-date, the client is kept intact. If not, or if it is a new relay, a new client will be created, and will
replace the previous one.

#### Port and proxy change

We add new entries to the `relayd` configuration file for these parameters, and pass them to the HTTP clients.

#### Service reload/restart

The service reload is triggered by the agent at each configuration or data file change.

### Agent configuration in `agent.conf`

A new `/opt/rudder/etc/agent.conf` or `C:\Program Files\Rudder\etc\agent.conf` (in toml like our other recent configuration files, but staying simple so that it can be parsed with grep) will allow configuring everything linked to agent-server communication, the rest being part of the policies.

```toml
# This file configures the way the Rudder agent connects to its server

# Port used for HTTP-like communication
https_port = 443

# Proxy configuration for HTTP-like communication
https_proxy = "https://user:password@proxy.example.com"
```

These parameters are read by the agent CLI which makes the `curl` calls.

### Apache httpd configuration

These changes also require modifications in our Apache httpd configuration, to properly configure
the node-server communication. In addition, we also cleaned up the configuration files to make them
more straightforward.

#### Packaged configuration files

The configurations provided in the packaging in 7.X:

* in the `rudder-relay` package:

  * `rudder-apache-relay-common.conf` is empty and kept for compatibility (in case people have old virtual host leftovers that included it)

  * `rudder-apache-relay-nossl.conf` redirects HTTP to HTTPS (and does nothing else)

  * `rudder-apache-relay-ssl.conf` handles all node-server communication, including the TLS certificate configuration.

    * ```apacheconf
      # certificate linked to agent keys
      SSLCertificateFile     /opt/rudder/etc/ssl/agent.cert
      # agent private key
      SSLCertificateKeyFile  /var/rudder/cfengine-community/ppkeys/localhost.priv
      ```

* in the `rudder-server` package:

  * `rudder-apache-webapp-common.conf` is empty and kept for compatibility (in case people have old virtual host leftovers that included it)

  * `rudder-apache-webapp-nossl.conf` is empty and kept for compatibility (in case people have old virtual host leftovers that included it), as redirection to https is handled by relay configuration.

  * `rudder-apache-webapp-ssl.conf` handles public Web/API communication (in particular reverse proxy to Jetty). It contains no TLS configuration.

All these files are provided by the packages and replaced by upgrades.

#### Virtual hosts configuration

The relay package also contains the configuration file containing the virtual hosts:

* `/etc/apache2/sites-available/rudder.conf` on Debian/Ubuntu/SLES
* `/etc/httpd/conf.d/rudder.conf` on RHEL

This file is marked as a configuration file in the package, and never replaced at upgrade.
It is the only Apache configuration file designed to be edited by the users.

It default skeleton is:

```apacheconf
<VirtualHost *:80>
  # include redirection to https
  Include /opt/rudder/etc/rudder-apache-relay-nossl.conf
</VirtualHost>

<VirtualHost *:443>
  Include         /opt/rudder/etc/rudder-apache-relay-ssl.conf
  # optional as webapp may or may not be installed
  IncludeOptional /opt/rudder/etc/rudder-apache-webapp-ssl.conf
</VirtualHost>
```

This means that both node-server and Web/API communications use the
same virtual host by default, and the internal Rudder certificate in particular.
It also provides commented configuration to allow using another certificate for
Web/API flows, with this skeleton:

```apacheconf
<VirtualHost *:443>
  Include /opt/rudder/etc/rudder-apache-relay-ssl.conf
</VirtualHost>

<VirtualHost *:443>
  # example with Letsencrypt
  SSLCertificateFile      /etc/letsencrypt/live/example.com/cert.pem
  SSLCertificateKeyFile   /etc/letsencrypt/live/example.com/privkey.pem
  SSLCertificateChainFile /etc/letsencrypt/live/example.com/fullchain.pem
  # contains no TLS configuration
  Include /opt/rudder/etc/rudder-apache-webapp-ssl.conf
</VirtualHost>
```

Note: It is also [now possible](https://docs.rudder.io/reference/7.2/administration/port.html) to change the ports used by these two virtual hosts.

## Migration

The 6.X setup was a single virtual host in `rudder.conf` which included:

```apacheconf
SSLCertificateFile      /opt/rudder/etc/ssl/rudder.crt
SSLCertificateKeyFile   /opt/rudder/etc/ssl/rudder.key
```

The `rudder.{key,crt}` were a key and self-signed certificate generated by the post-installation script, only for this Apache configuration.
As this key is meaningless, all HTTPS calls to the relay and server ignored the certificate validation by default.

To allow improving security we [documented](https://docs.rudder.io/history/6.2/reference/6.2/administration/security.html#_setup_root_server) how to replace these generated files with valid key/certificate. This allowed proper browser validation, and we also provided a setting to validate HTTPS certificates in calls from agents.
We hence have three possible cases on upgrade:

* Default settings, using the self-signed certificate and no certificate validation
* A custom certificate, without certificate validation for Rudder flows
* A custom certificate, with certificate validation for Rudder flows

The relevant post-installation script from the relay package:

```shell
# Backup and remove old HTTPS key and cert
if [ -f /opt/rudder/etc/ssl/rudder.crt ]; then
  mv /opt/rudder/etc/ssl/rudder.crt "${BACKUP_DIR}/rudder-`date +%Y%m%d`.crt"
fi
if [ -f /opt/rudder/etc/ssl/rudder.key ]; then
  mv /opt/rudder/etc/ssl/rudder.key "${BACKUP_DIR}/rudder-`date +%Y%m%d`.key"
fi
if [ -f /opt/rudder/etc/ssl/ca.cert ]; then
  mv /opt/rudder/etc/ssl/ca.cert "${BACKUP_DIR}/ca-`date +%Y%m%d`.cert"
fi

# Internal certificate is now in a packaged file, remove references from
# virtual hosts. Edition needed as it is not replaced on upgrade.
sed -i '/SSLCertificateFile.*\/opt\/rudder\/etc\/ssl\/rudder.crt/d' /etc/${APACHE_VHOSTDIR}/rudder.conf
sed -i '/SSLCertificateKeyFile.*\/opt\/rudder\/etc\/ssl\/rudder.key/d' /etc/${APACHE_VHOSTDIR}/rudder.conf
```

The old `rudder.{crt,key}`/`ca.cert` files are removed for clarity as we don't want to keep unused
files in the configuration, but this also moves custom certificates as they usually live in the same path in 6.X.

For people using a custom certificate in 6.X, 7.X migration requires **manual changes** to the virtual host configuration. We made an [upgrade note](https://docs.rudder.io/reference/7.2/installation/upgrade/notes.html#_https_certificate), but as expected it caused several migration issues for users.
A common error pattern for users is to continue placing their certificates in the previous path,
which is now removed at each upgrade.

The required changes are to comment the default configuration and enable the split virtual hosts, and most importantly to add a discriminator for the network flows. It can be:

* Different hostnames as done in the example configuration. In general, the Web/API access uses
  a specific host and the node-server doesn't. In this case it works as the default virtual host is the node-server one.
* Different ports, also documented in the [dedicated page](https://docs.rudder.io/reference/7.2/administration/port.html).
* Different source IP ranges, etc.

The custom certificates can be placed _anywhere_, apart from their previous location in `/opt/rudder/etc/ssl/rudder.{crt,key}` as the upgrade script will remove them.