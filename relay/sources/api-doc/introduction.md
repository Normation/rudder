Download OpenAPI specification: [openapi.yml](openapi.yml)

**Other documentation sources**:

* [Main documentation](https://docs.rudder.io)
* [Public API](https://docs.rudder.io/api/)

# Introduction

Rudder relay exposes an internal API, enabling the agents and other services to interact with the relay.

Apache proxies the remote-run, shared-files, and shared-folder routes from
`https://relay.example.com/rudder/relay-api/` to `http://localhost:3030/rudder/relay-api/1/`.
Apache serves policy files for every managed node at
`https://relay.example.com/policies/{nodeId}/{path}`. The path is relative to that node's policy
directory, for example `rules/cfengine-community/promises.cf` for CFEngine or
`rules/dsc/rudder.zip` for the Windows DSC archive.

## Authentication

The relay API is authenticated on a case-by-case basis:

* *Remote run* is only accessible to requests from the IP of the relay's own policy server

* *Shared files* checks file signature based on known public keys from nodes

* *Shared folder* is only available to nodes in the authorized networks

* *System* routes are available on the local relay API.

* *Policies* downloads require the node's client certificate and access from an authorized network.

## Versioning

Each time the API is extended with new features (new functions, new parameters, new responses, ...), it will be assigned a new version number. This will allow you to keep your existing scripts (based on previous behavior). Versions will always be integers (no 2.1 or 3.3, just 2, 3, 4, ...).
On the local relay API, versioned routes use `/rudder/relay-api/{version}/{action}`. The Apache policy download URL is unversioned.
In the future, we may declare some versions as deprecated, in order to remove them in a later version of Rudder, but we will never remove any versions without warning, or without a safe period of time to allow migration from previous versions.

### Existing versions:

<table>
  <thead>
    <tr>
      <th style="width: 20%">Version</th>
      <th style="width: 20%">Rudder version it appeared in</th>
      <th style="width: 70%">Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td class="code">(none)</td>
      <td class="code">3.2</td>
      <td>Unversioned API providing remote-run, shared-files and shared-folder</td>
    </tr>
    <tr>
      <td class="code">1</td>
      <td class="code">6.0</td>
      <td>First relay API with a version</td>
    </tr>
  </tbody>
</table>


## Response format

The *System* API and future endpoints use JSON with the following schema:

```json
  {
    "action": The name of the called function,
    "result": The result of your action: success or error,
    "data": Only present if this is a success and depends on the function, it's usually a JSON object,
    "errorDetails": Only present if this is an error, it contains the error message
  }
```
