# Rudder 8 - Policy distribution

*Notice*: This document was written as part of preliminary work for Rudder 8, some parts may be outdated.

## Overview

This document discusses the current state of policy distribution mechanisms, the problem we have (especially for Unix agents)
and the possible solutions to them.

## Context

Rudder agents have several different interaction types
with their policy server:

| Type                             | Unix              | Windows     |
|----------------------------------|-------------------|-------------|
| send reports                     | HTTPS             | HTTPS       |
| send inventories                 | HTTPS             | HTTPS       |
| share a file with another node   | HTTPS             | HTTPS       |
| receive a remote run trigger     | CFEngine          | N/A         |
| download shared files on relay   | CFEngine OR rsync | HTTPS       |
| download nodes policies on relay | CFEngine OR rsync | HTTPS + ZIP |
| download shared files on node    | CFEngine          | HTTPS       |
| download policies on node        | CFEngine          | HTTPS + ZIP |

In Rudder 7 we have unified the credentials used by both protocols
(same key pair + certificate).

### Unix

#### CFEngine's network protocol

The policy download mechanism uses CFEngine file copy protocol.
This protocol is kind of a "dumb rsync".

The problem is that the synchronization of the relay's own policies takes too long. Note that it is only the relay's inputs+ncf, as nodes policies are synchronized using rsync.

#### Rudder implementation

We rely on CFEngine's protocol to copy our policies from server to relay, and server or relay to node.
We have two separate policy sources, a common library that is identical everywhere, and a node-specific part.
In addition, we also distribute files that the policies can download, either from a global shared directory, or node to node file exchange.

For both policy folders, and given the amount of files to synchronize,
we have a flag mechanism to trigger a scan.
The the folder on the server contains a file, which itself contains the date of last modification in the folder.
In case it is newer, the agent will synchronize the file. In case it is not, policies are considered up to date.

TODO rsync

#### Performance

The CFEngine copy protocol is very sensitive to latency, as
everything is done sequentially, files are treated one-by-one
and a file update takes three round trips (stat+copy+hash).
This is especially a problem for relays:

* big amount of files (~ `300 * num_nodes`)
* often distant sites, with high latency (hundreds of ms)

This can lead to update time that take longer than 5 minutes,
and policy update processes piling up, leading to various errors
leading to policy corruption (`Can't stat new file '...' - another agent has picked it up?`, `New file '...' seems to have been corrupted in transit, destination 0 and source 2345, aborting.`).
Policy corruption then leads to a reset by rudder agent check, 
which in turn leads to a full policy download, and more
problems. The situation can take some time to converge, as the cron job will reset the policies itself when they are invalid (which they can be in the middle of the update), restarting everything from the beginning.


* The problem is notably triggered by your provisioning playbook which runs "rudder agent server-keys-reset -f", which will in turn trigger a policy reset.
* You have found a workaround which consists of:
    * Disabling the agent on the relay
    * Running a single update with rudder agent update -f
    * Running a single run with rudder agent run -f
    * Re-enabling the agent

#### Numbers

* lib (`ncf`)
    * 278 files
    * 2.2MB uncompressed
        * including a useless 378KB `generic_methods.json` file
    * 1.8MB uncompressed without useless files
    * 227KB compressed with gzip
    * 155KB compressed with xz
* policy (`inputs`)
    * 120 files
    * 1.1MB uncompressed
    * 107KB compressed with gzip
    * 75K compressed with xz

| uncompressed | gzip    | xz      |
|--------------|---------|---------|
| 398 files    | 2 files | 2 files |
| 2.9MB        | 384KB   | 230KB   |

Note: We could also greatly reduce the volume by minifying the files (i.e. at least removing comments, which by itself gain 10-20%).

There is an important nuance to this: while the current mechanism
uses a rsync-like process to only download updated files,
used an archive requires downloading the full policies at each
update.
So while it would be a clear gain in policy synchronization and update
times, the effect on network consumption is hard to valuate,
as it depends on policy content and generation frequency.

#### Security

In terms of trust the CFEngine protocol leads the HTTPS on Unix agents, as the pinned public key is part of the policies.

The CFEngine security model is limiting for us, as it requires RSA keys
and peer to peer validation, which prevents easily using
customer PKI infrastructure.

### Windows

The Rudder Windows agent uses only HTTPS to communicate with the server.
The client authentifies using its certificate as client certificate in HTTPS, and the server identity is enforced by 
public key pinning on the agent (with TOFU, the pinned key is included in the first policies downloaded).

### General plans

The general direction for Rudder node-server communication is:

* Move towards full HTTPS
* Be more flexible for certificate/PKI choices

---

## Changes

We need to keep compatibility with CFEngine's copy protocol for complex (i.e. with filters) recursive downloads form shared-files which are not easy to implement over HTTPS.
The main use case for these features in the **File download (Rudder server)** technique. General recursive downloads like the **File from remote source recursion** method are also affected.


Two zip files:

* one for the lib (a.k.a. "ncf"), changing at each upgrade
* one with the policies, changing at each policy generation

Another advantage is the ability to make atomic policy updates,
preventing most policy corruptions. 

    Work on a policy update locking mechanism for Rudder 7.3 which would avoid requiring this workaround
    Add an alternative policy update methods, less sensitive to latency, very likely an archive download through HTTPS (as we already do for our Windows agent).

### Security

Having atomic archives opens a door for end-to-end
policy signature and encryption.

### Performance


---

## Transition

A new policy synchronization for Unix agents would be available as an
option, while keeping the previous one for compatibility (like we did for HTTPS reporting).

## Time-line

### 2023 Q2: First preliminary studies

