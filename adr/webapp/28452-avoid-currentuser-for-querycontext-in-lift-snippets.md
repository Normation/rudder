# Avoid using CurrentUser to get QueryContext in Lift snippets

* Status: accepted
* Deciders: CAN, PIO, FAR
* Date: 2025-03-01
* Updated for details about root cause explanation: 2026-06-10

## Context

The webapp makes use of Liftweb [snippets](https://exploring.liftweb.net/master/index-5.html#entry-Snippets-0).
Several snippets require that the user is authenticated, and use that information for display.

For that purpose, the authenticated user is provided into a `CurrentUser` global and static variable.
But this has a known limitation:
* the variable is set for each HTTP request in a thread local due to the way spring-security filter works,
* a variable lookup would return no user if there is a thread context switch, which happens when ZIO runs its effects.

Because of that inconvenience, there are security and auditability issues, such that the current user and their tenants are not known.
The object `QueryContext` holds the currently authenticated user and their tenants for that purpose, and is not known in that case.

Example problematic cases are described in https://issues.rudder.io/issues/29034#note-2. 
The specific case to avoid is calling `f(x)(using CurrenUser.queryContext.getOrElse(...)).runNow` or any ZIO runtime evaluation method
taking a by-value argument leading to `CurrentUser.queryContext` getting resolved in the ZIO thread pool context. 

## Decision

Starting from now, we use a safety net guaranteeing that the `QueryContext` is defined, namely **snippets strictly initialized with the `QueryContext` of the authenticated user**.

The `SecureDispatchSnippet` safe snippet is introduced in [this issue](https://issues.rudder.io/issues/28404), here is the implementation that is proposed:
```scala 3
trait SecureDispatchSnippet extends DispatchSnippet {
  def secureDispatch: QueryContext ?=> DispatchIt

  override def dispatch: DispatchIt = {
    CurrentUser.queryContext.withQCOr(loggedInsecureDispatch)(secureDispatch)
  }

  private def loggedInsecureDispatch: DispatchIt = {
    ApplicationLoggerPure.Auth.logEffect.warn(s"Snippet can't be accessed in current security context (user is not authenticated)")
    PartialFunction.empty
  }
}
```

For snippet `val` instantiated by snipped constructor (for example for stateful snippets) via a `f(x)(using qc:QueryContext)` function, 
the call to `CurrentUser.queryContext` MUST be done in another expression than the one passed to `runNow`: 

```scala 3
given qc: QueryContext = CurrentUser.queryContext.getOrElse(throw new RuntimeException())
//...
private val x = f(x)
```
Or with an explicit call:
```scala 3
val qc: QueryContext = CurrentUser.queryContext.getOrElse(throw new RuntimeException())
// ...
private val x = f(x)(using qc)
```



This will :
* enforce that extending snippets are provided with a `QueryContext`,
* display nothing and do some logging in case the `QueryContext` cannot be looked up due to the aforementioned issue.

## Consequences

* Migrate snippet that need the `QueryContext` to implement the safe snippet interface.
* Prevent using the `CurrentUser#queryContext` in other potentially unsafe places.
