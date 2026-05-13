# Elm initialization and handling of authorizations

* Status: accepted
* Deciders: CAN, FAR, PIO, RGI, VHA, VME
* Date: 2026-04-10

## Context

The webapp frontend uses Elm applications for the UI of Rudder, in favor of the legacy Liftweb server-side templating, dependency that we want to get rid of some day.

We have been increasingly adding new Elm applications, mostly in isolated parts (which means routing is still handled by Liftweb).
Most of them require loading some content from the backend for initialization.

There was several ways to initialize the Elm app with such content : 
1. by using the "init" part, and passing data through Elm flags
2. by making API calls to the REST API

The access to the data is also subject to the concern of authorization, which we respectively handle in different ways, described as follows :
1. using Liftweb, which is the old way
2. using  Elm and our RESTful API, which is the one we should opt for

### 1. Using Liftweb
Since Liftweb snippets allow to have surrounding HTML tags for displaying content only, we use `<lift:authz role="administration_read">...</lift:authz>` 
So, we often use it to set Elm flags the following way :
 - initializing a global javascript variable as a solution to pass the actual boolean value during Elm initialization
 
 ```html
 <script>
   var hasAdminRead = false;
 </script>
 <lift:authz role="administration_read">
   <script>
     hasAdminRead = true;
   </script>
 </lift:authz>
 ...
 <script>
   Elm.App.init({ hasAdminRead }, ...)
 </script>
 ```
 
 - and use it dynamically in the Elm app
 
 ```elm
 init : { hasAdminRead : Bool } -> ( Model, Cmd Msg ) 
 init flags =
   ( { ... | ui = { initUi | hasAdminRead = hasAdminRead } }, if hasAdminRead then getContent else Cmd.none )
   
 view model =
   if model.ui.hasAdminRead then
     viewRestrictedContent
   else
     viewNoRights
     
 getContent =
   Http.get "/api/admin_read" ...
 ```
      
  
This presents several flaws in the design : 

- there is **strong coupling** between the Elm application : there is stateful snippet code that initializes it
- there is **redundancy**: the flag check is redundant with the content that should already be under restricted access by the API, if the API has proper 401/403 response codes

In order to have a stateless design (Elm and API calls), we would need :

- proper RESTful response from the API for authorizations (return 401 generically when no means of authentication is provided, return 403 when rights are insufficient to access the content, …)
- handling of the response from the REST API in the logic flow of the Elm app

### 2. Using Elm and the RESTful API
  
So, to properly handle authorization we could load it the following way :

```elm
view model =
  if hasRights model then
    viewRestrictedContent
  else
    viewNoRights

getContent =
  Http.get "/api/admin_read"
    |> handle403With noRightsModel
```

Which means, there is no flag, and behavior of the Elm apps relies on a single source of truth which is the API it gets content from.
Here there is NO dependency to Liftweb, and we could initialize the Elm app from static HTML with a minimal `<script/>` tag.

## Decision

* ❌ Avoid making use of Liftweb for Elm apps, this would avoid those antipatterns:
  * do not pass authorization checks as flags using Liftweb
  * do not depend on snippets variables and logic to initialize the Elm _model_
* ✅ Initialize Elm applications with the least dependencies:
  * use the RESTful API to properly check authorizations and access, using response codes to handle logic flows of authorization
  * reflect the case of "having no authorizations" in the Elm _model_ and _update_

## Consequences

* There could be other concerns when API authorization model evolves, and if we have more complex apps that handle their data with routing, …
* We rely on the behavior of the RESTful API, which therefore needs to be consistent at all times
* We would need to consider migrating away from the Liftweb pattern, in order to eliminate Liftweb code and get rid of the dependency
