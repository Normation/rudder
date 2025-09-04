# Rudder 9.0 : HTTPS certs & HTTP policy update on Linux

## Overview

Rudder inherits its network communications security model from CFEngine and early Rudder versions.

In this document, we call **Full HTTPS mode** the operating mode of Rudder, where the CFEngine protocol is not used (and the `cf-serverd` service disabled). We call **Full CA-based mTLS** the operating of mode of Rudder, built on top of the full HTTPS mode, where all certificates are verified against a CA.

### Goals

* HTTPS_ONLY : seulement sur server
* USE_HTTPS : partout pour l'agent
* CA_VERIFY : hostname et/ou uuid
* PINNING vs VALIDATE
    * pas de pinning en mode validate

* On ne supporte pas de toucher aux clé/certif pas en HTTP_ONLY
    * format des clés potentiellement

* WebDAV : inventaires et reports

Fonctionnalité : templatisée :
config par défaut vide, génère une conf au premier run

un peu refactoré,


Apache : conf de base
générée au packaging, version pinning


TODO documenter : bien vérifier que ça a switché

–

pas de procédure pour switcher d'une instance pinning vers validation : faudra passer chaque machine changer les certifs, et il faut tout changer pour que ça remcarche.

TODO : ajouter un check certif : présence de l'uid dans le certif

### Non-goals

* We don't intend to manage the certificates in Rudder. We are not implementing a PKI infrastructure but allowing to use an existing one.
* We continue using OpenSSL for TLS everywhere.

## Current situation

We currently have two different models for agent-server communication:

* On Linux
* On Windows

It emerged from the CFEngine protocol, which was extended while keeping compatibility. An important feature is the absence of required configuration for authentication to work.

But it is also challenging:

* The usage of self-signed certificates is flagged by auditors, and causes problems with users.
* More generally, the certificate workflow makes TODO
* The CFEngine protocol has serious flaws:
    * It uses a service `cf-serverd` implemented in C, running as root, and listening on the network, which is a bad combination.
    * It has terrible performance on high latency networks.
    * Uses MD5 (for public key pinning), which prevents (or limits) usage in FIPS environments.

### Goals

* Provide a full-HTTPS mode for Linux nodes
    * It will support policies update and single file copy from the server
    * It won't support recursive file copies from the server
    * It won't support remote-run
    * It won't support relays for now (due to the need for recursive copy for node policies)
* Provide a Full-CA-based mode for HTTPS communications
    * The PKI management is done by the user
    * It can use a CA provided on the root server, or the system CA store.
    * It will only work in full HTTPS mode
    * The pinning is disabled (as certificate longevity is shrinking and we have no update automation)

## Certificates management

The goal for now is to keep the same default mode (TOFU with self-signed certificates), but add a new mode based on more standard certificate validation. In this mode:

* we don't manage the certificates at all, and the user is responsible for managing the PKI.
* we don't change the CFEngine side. The only way to get certificate validation everywhere is to use the HTTP policy download (and disable `cf-serverd` everywhere).

This section only discuss the HTTP side.

Note: We use OpenSSL everywhere for TLS (including in Rust programs).

### Configuration

We have two sides:

* Configuration on the policy server side, distributed as part of the policies.
* Configuration on the node side, required for proper bootstrapping.

As a general principle, the configuration coming from the policy server is persisted on the nodes to avoid downgrade attacks. This also means switching back to the default communication mode requires a manual intervention on each node.

#### Server

In `/opt/rudder/etc/rudder-web.properties`:

```
#
# There are two modes for node-server security configuration:
#
# * A built-in mode using self-signed certificates, TOFU and public key pinning.
#   This works without any specific configuration.
#   This mode uses two different protocols be default, but it can be configured to
    use HTTPS only.
# * A configurable mode using standard certificate validation. In this case the whole PKI
#   is handled by the user. This mode requires the HTTPS only.
#

# Value for additional sever key hashes.
# Will be used to trust more than one server certificate in HTTP communications, typically for migration.
# Await a semicolumn separated string array, on one line or with `\` at end of line because we are
# in a properties file:
#   rudder.server.certificate.additionalKeyHash=sha256//Pxjkq/Qlp02j8Q3ti3M1khEaUTL7Dxcz8sLOfGcg5rQ=;...
#
# Default: empty.
rudder.server.certificate.additionalKeyHash=

#
# When enabled, the CFEngine network communications are completely disabled.
#
rudder.server.certificate.httpsOnly=false

#
# Enable the certificate validation mode. Requires a proper PKI managed by the user
# When false, the default public key pinning mode is used.
#
rudder.server.certificate.validation=false

#
# Value for the path to the file with the CA cert that should be used
# for server certificate validation.
# Let empty to use system CA list.
# Default: empty.
#
rudder.server.certificate.ca.path=

```

* `additionalKeyHash` is not used in certificate validation mode.
* `ca` is used when provided, in place of the system store
* `nameValidation` changes the validation mode of all HTTP connections.
* `https_only` disables CFEngine-based file copies
    * Document how to remove CFEngine policies

This is translated as in the `rudder.json` file distributed in all node's policies as:

```
{
  "POLICY_SERVER_KEY_HASH": "sha256//LqSz+lTXd9VN4qhpQfGagTrQJjw/msKoczOc4XddhkA=;sha256//Pxjkq/Qlp02j8Q3ti3M1khEaUTL7Dxcz8sLOfGcg5rQ=",
  "POLICY_SERVER_CERT_CA":"-----BEGIN CERTIFICATE-----\nMIIFqTCCA5GgAwIBA...s/wCuPWdCg==\n-----END CERTIFICATE-----",
  "POLICY_SERVER_CERT_VALIDATION": "true",
  "POLICY_SERVER_HTTPS_ONLY": "true"
  ...
}
```

#### Relayd

In `/opt/rudder/etc/relayd/main.conf`:

```toml
peer_authentication = "cert_pinning" # or cert_validation
# used when provided
ca_path = "tests/files/http/cert.pem"

https_port = 443
https_idle_timeout = "12s"
```

#### Agent

In `/opt/rudder/etc/agent.conf`:

```ini
# server = 
# proxy_https = 
# https_port = 

https_only=true
cert_validation=true
ca_path=/etc/ssl/myCA.cert
```

These settings are mostly used as default before downloading the first generated policies. Here `https_only` is an exception as the setting stays local.

NOTE: There is a special case on servers as disabling `cf-serverd` would prevent any agent from synchronizing using the CFEngine protocol. This setting must be used with care.

### Implementation

Two components make HTTPS calls: the agent and relayd. The HTTPS on the server side is handled by apache httpd.

#### Rudder agent

##### Authentication

Authentication is achieved using client certificate sent by curl:

```
--cert /opt/rudder/etc/ssl/agent.cert --key /var/rudder/cfengine-community/ppkeys/localhost.priv
```

On Linux and Windows agents.

Note: We also have WebDav authentication for file upload. We keep it for now but it will likely be removed when we switch to plain HTTP APIs.

##### Pinning

On the agent use public key pinning in curl ([`--pinnedpubkey`](https://everything.curl.dev/usingcurl/tls/pinning.html) option). It takes a list of sha256 hashes and compares them to the public key extracted from the certificate presented by the server.

The pin value comes from inside the policies:

* `INPUTS_PINNED_HASHES` in `rudder.json` in priority
* `/var/rudder/lib/ssl/policy_server_hash` used as cache when policy reset, avoiding to lose trust

We disable pinning when validating the certificates (as it would make certificate renewal harder for no big win).

##### CA validation

We have three cases:

* Use pinning only, without certificate validation (default)
* Use the system CA store to validate the certificate
* Use a specific CA to validate the certificate

```
# we have to provide --capath so that curl doesn't look in default truststore
--capath ${POLICY_SERVER_CERT_CA} --cacert ${POLICY_SERVER_CERT_CA}
```



Notes:
- rudder-client uses policy_server.dat to find it policy-server
- rudder-client uses agent.conf get its proxy parameters
- rudder-client uses rudder.json for server validation parameters

Server validation:
- when no parameter is present, rudder-client uses the policy_server_hash if it exists to check the server key
- when the POLICY_SERVER_KEY_HASH parameter it defined, rudder-client trusts any server having one of these hash
  POLICY_SERVER_KEY_HASH must be of the form sha256//<hash1>;sha256//<hash2>;...
- when POLICY_SERVER_SECURE_VALIDATION is true
  rudder-client applies POLICY_SERVER_KEY_HASH rules if defined
  rudder-client checks that the server certificate is signed by a trusted CA
  rudder-client checks that the server certificate valid and its subject CN matches the URL
- when POLICY_SERVER_CERT_CA is defined, the default trust store is replaced by this CA certificate
  POLICY_SERVER_SECURE_VALIDATION must be true for this option to be useful

#### rudder-relayd

* TLS 1.3 enforcement: https://github.com/sfackler/rust-native-tls/pull/278
*

FIXME : disable remote-run in https_full

##### Authentication

##### Pinning

The relay uses a different mechanism (as the tooling did not provide the required APIs when implemented). We create [a specific HTTP client](https://github.com/Normation/rudder/blob/789df0cc663ba873088aa41bdefdb27edca18ff9/relay/sources/relayd/src/http_client.rs#L62C5-L73C1) for each server we talk to, with its certificate configured as only root certificate.

##### CA validation


#### Apache httpd

Currently

#### Webapp

The webapp does not directly talk with the nodes. It talks to relayd on loopback, unencrypted, only for remote run. This is disabled in HTTPS-only mode.

### Migration




## HTTPS policy update

The changes:

* The authentication logic, as until now the CFEngine protocol has the priority, and set the pinning for HTTPS communications.

## Timeline

The goal is to get

* si vous avez du 5309 alors générez votre nouveau certificat dans changer la clé (et ca simplifie encore plus la procédure en fait)
* si ca vous convient pas reprenez l'ancienne procédure et ne changez que le certificat web
* sa ca ne vous convient pas, passez au full https

* Q32025: 8.3.3
    * First changes in `rudder-client` and the Webapp to allow using a custom CA or system store to validate server certificates on agents.

* Q42025: 9.0



---

il me semble que côte http policies il manque la partie webapp
on génère les deux et le client choisit


zip desactivable
virer après compression
bootstrap avec agent.conf




mode verrouillage lockdown (http+full ca), desac cfserverd

voir avec fdall pour windows



auth par cert : comment on fait les ACL de DL de policies (aujourd'hui UUID). Par hostname ??


supporter plusieurs CA cutom

accepter l'asymetrie
on ne se repose pas sur le dns des clients

https://cyber.gouv.fr/sites/default/files/document/secnumcloud-referentiel-exigences-v3.2.pdf#%5B%7B%22num%22%3A113%2C%22gen%22%3A0%7D%2C%7B%22name%22%3A%22XYZ%22%7D%2C73%2C713%2C0%5D


TOUDOUX:

* [ ] native tls fork
* [x] desactivation de cf-server par l'agent
* [x] template mustache pour la conf apache
* [x] conf agent
* [ ] adaptation Windows
    * [x] Virer RudderCurl
    * [ ] Adapter RudderClient
* [x] virer rsync de relayd
* [ ] Cheker CRL dans le cas custom
* [x] Stocker les valeurs de ca/https only dans l'agent : pour raison de secu c'est un cliquet, empeche de downgrade
* [x] stocker la valeur du port https parce qu'en http only on a pas de recover
* [x] D´ésactiver toutes la gestion des clés certif en cas de CA (dans les scripts d'agent)


* le reset doit aussi forcer un redl HTTP

on peut virer le localhost.pub


est-ce que CFEngine aime les clés localhost.priv qu'il ne connaît pas ????


stockage de la CA custom


ajout check lien httptsonly vs cert vlid
