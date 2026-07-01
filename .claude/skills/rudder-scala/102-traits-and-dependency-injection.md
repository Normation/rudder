# 102 — Traits & dependency injection

## Program to interfaces (traits)

Every service and repository has a **trait** defining its API — *even when there is a
single implementation*. Business code depends on the trait; the concrete `*Impl` is
an implementation detail.

```scala
trait PropertiesRepository {
  def getAllGroupProps(): IOResult[Map[NodeGroupId, ResolvedNodePropertyHierarchy]]
  def getNodeProps(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Option[ResolvedNodePropertyHierarchy]]
  def saveNodeProps(props: Map[NodeId, ResolvedNodePropertyHierarchy]): IOResult[Unit]
  def deleteNode(nodeId: NodeId): IOResult[Unit]
}
```
(`rudder-core/.../properties/PropertiesRepository.scala`)

Trait method signatures are the contract: they return `IOResult[...]` for anything
effectful (see [`300`](300-effects-zio-ioresult.md)), take typed domain inputs
(see [`201`](201-parse-dont-validate.md)), and may carry context like
`(implicit qc: QueryContext)` for security scoping (see [`600`](600-security-in-depth.md)).

## Dependency injection is constructor-only

**Do not** use ZIO's environment (`ZLayer`, `R` in `ZIO[R, E, A]`) for DI. Our `R` is
always `Any`. Wiring is done by **constructor parameters**, and the whole object graph
is assembled in one place: `RudderConfig`
(`rudder-web/src/main/scala/bootstrap/liftweb/RudderConfig.scala`).

`RudderConfig` is, in its own words:

> "This is not a cake-pattern, just a plain object with load of lazy vals."

```scala
lazy val pluginSystemService  = new PluginsServiceImpl(...)
lazy val rudderPackageService = new RudderPackageCmdService(RUDDER_PACKAGE_CMD)
lazy val workflowLevelService = new DefaultWorkflowLevel(...)
```

Each component takes its collaborators (as traits) in its constructor and is
instantiated once as a `lazy val`. Dependencies flow in; nothing reaches out to a
global registry or service locator.

## Rules of thumb

- A new service/repository = **trait** (the port) + **`*Impl` class** (the adapter)
  whose constructor takes the traits it needs.
- Wire the new impl as a `lazy val` in `RudderConfig`, passing the already-declared
  collaborators.
- **No** `ZLayer`/`ZIO` environment, **no** cake pattern, **no** runtime DI framework.
  (A single module, `SettingsApi`, passes a service via `ZIO` env + `provideLayer`
  instead of by constructor — that's a known exception to avoid copying, not the
  pattern.)
- Keep constructors honest: if a class needs a collaborator, take it as a parameter —
  don't reach for a singleton.
- Test doubles are just alternative trait implementations passed to the constructor.
