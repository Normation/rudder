# Avoid using CurrentUser to get QueryContext in Lift snippets

* Status: accepted
* Deciders: CAN, PIO, FAR
* Date: 2025-03-01


## Context

The webapp makes use of Liftweb [snippets](https://exploring.liftweb.net/master/index-5.html#entry-Snippets-0).
Several snippets require that the user is authenticated, and use that information for display.

For that purpose, the authenticated user is provided into a `CurrentUser` global and static variable.
But this has known limitations, such that :
* the variable can only be set for each HTTP request, due to all the servlets and spring-security plumbings,
* a variable lookup would sometimes return no user, even if there is actually an authenticated user (when used with the `ZIO` framework in latest versions, see [the corresponding issue](https://issues.rudder.io/issues/26605)).

Because of that inconvenience, there are security and auditability issues, such that the current user and their tenants are not known.
The object `QueryContext` holds the currently authenticated user and their tenants for that purpose, and is not known in that case.

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

This will :
* enforce that extending snippets are provided with a `QueryContext`,
* display nothing and do some logging in case the `QueryContext` cannot be looked up due to the aforementioned issue.

## Consequences

* Migrate snippet that need the `QueryContext` to implement the safe snippet interface.
* Prevent using the `CurrentUser#queryContext` in other potentially unsafe places.
