# Framework for dynamic checking of plugin status in their menu entries and APIs

* Status: accepted
* Deciders: FAR, CAN
* Date: 2026-03-26

## Context

Plugins can be enabled or disabled based on the validity status of their license. That status is dynamic and can 
change when Rudder is running, based on contextual factors like time passing or new nodes being managed. 

Access to resources offered by the plugin like menu entries or API endpoint MUST reflect that dynamic status of 
the plugin: 
- if a plugin becomes enabled (resp. disabled), then its corresponding menu entries must appear (resp. disappear) in Rudder UI after a page reload,
- if a plugin becomes enabled (resp. disabled), then its corresponding API endpoints must work (resp. return an error telling that the plugin is disabled).

Until https://github.com/Normation/rudder/pull/7022 and related plugins PR https://github.com/Normation/rudder-plugins/pull/935 
and https://github.com/Normation/rudder-plugins-private/pull/1332, the code logic to check for these things was ad-hoc and
let at the willingness of the developer. This is not principle and error prone, especially since the logic is complicated, 
time and environment related, difficult to test, and spans between Rudder core, plugins, several git repository, and several
non-trivial frameworks (Rudder API framework and Lift).

It often led to bugs like ["When license status change, the security benchmarks menu is not always updated"](https://issues.rudder.io/issues/28246).


## Decision

The decision was made to: 
- normalize the way plugin's menu entry and APIs are checked with regard to plugin status, 
  - the API check is done centrally, in the Rudder API framework, and abstract plugin signature is updated to force developers to use the corresponding logic,
  - the menu logic is checked by a standard methods holding the logic,
- the whole design is made as easy to use as possible by developers thanks to implicits and correct defaults, 
- the design has the ability to override the implicits (for a whole set of endpoints, typically in tests) and override locally (for only one API implementation or one menu entry that needs to be always enabled).

## Consequences

### Plugin menu entry

A new method is provided that take care of the plugin status to display or not the menu entry: `testMenuAccess`. Use it in your menu definition in plugin definition in place of the standard `TestAccess`: 

```
class MyPluginDef(override val status: PluginStatus) extends DefaultPluginDef {
  ...

  override def pluginMenuEntry: List[(Menu, Option[String])] = {
    (
      (Menu(
        "300-myplugin",
        <span>My Plugin</span>
      ) /
      "secure" / "myplugin" / "manage it"
      >> testMenuAccess(Node.Read)
      >> Template(....).toMenu,
      Some("300-myplugin")
    ) :: 
    ...
 }
 ```


### Plugin & API Modules

*Checking if API is enabled*

This is now the responsibility of the framework and not the developer of an API endpoint. 

`ApiModule` (our standard trait for what an API implementation is) is extended to have an `def isEnabled: Boolean` method. 
This is not done in the endpoint schema, because it was making test infrastructure coupled to `RudderConfig` infrastructure, which is unwanted. 

The check is centrally done in the same place as authorization related checks for API access. 

The core endpoint's implementation are statically defined to always return `true`. 

*In plugins*

Plugin module definition signature for API is updated to use a new plugin specific API provider: 

```
trait RudderPluginDef {
  ...
  def apis: Option[PluginLiftApiModuleProvider[? <: EndpointSchema]] = None
  ...
}
```

In the plugin API implementation, the class must extends `PluginLiftApiModuleProvider` that provides an implicit value linked to `PluginStatus` :

```
class MyApiImpl(....)(using status: PluginStatus) extends PluginLiftApiModuleProvider[API] {
  def getLiftEndpoints(): List[LiftApiModule] = {
  // nothing change here in the mapping between endpoint schema and implementation
  }
  
  // An implementation await the implicit `status: PluginStatus` automatically provided by
  // PluginLiftApiModuleProvider so nothing change in the implementation
  object AStandardImplementOfEndpoint extends LiftApiModule {
    ...
  }
  
  // one can locally override `isEnabled` if needed: 
  object AnAlwaysEnabledAPI extends LiftApiModule {
    ...
    override def isEnabled: Boolean = true
  }
}

```
*Providing the implicit `PluginStatus`*

The implicit `PluginStatus` is needed each time `MyApiImpl` class is instanciated, which normally 
happens at two places: 

- in the plugin `Conf` object which extends `RudderPluginModule` and where its services are defined. Here, we have access to `PluginStatus` thanks to `pluginStatusService` (always defined in that object)

```
object MyPluginConf extends RudderPluginModule {
  ...
  given apiStatus: PluginStatus = pluginStatusService

  lazy val api = new MyApiImpl(...)
  ...
}
```


- in test. Here, we want to have a definitive value for the status. We can use the `AlwaysEnabledPluginStatus` class for that: 

```
  val testMyApi = new MyApiImpl(...)(using AlwaysEnabledPluginStatus)
```
