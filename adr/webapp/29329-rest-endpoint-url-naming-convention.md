# Url REST endpoint naming convention

* Status: accepted
* Deciders: @dev-team
* Date: 2026-07-31

## Context

We noted a lack of standard in url naming in the REST API which led to refactor the compliance API.

The standard when creating REST endpoint is to make the conception around resources.

Benefits:
- standardize our APIs
- by reading the url signature it's very clear what does the endpoints
- easy for users to discover APIs by themseleves

## Decision

Follow these patterns
```
<METHOD> resources ? offset=0&limit=10 
<METHOD> resources / {action}
<METHOD> resources / {resource-id}
<METHOD> resources / {resource-id} / subresources ? offset=0&limit=10 
<METHOD> resources / {resource-id} / subresources / {action}
<METHOD> resources / {resource-id} / subresources / {subresource-id}
```

Use plural nouns to represent resources.
Use verbs to represent action.

## Consequences

The compliance API is currently refactored. 
Future endpoints will follow this convention.
