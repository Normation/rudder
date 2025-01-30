# Rudder early init process

This is the boot process of Rudder. Each bullet indent means that the following
points happen in the parent bullet point. 

- Jetty web server starts
  - reads start-up config in /opt/rudder/etc/rudder-jetty.conf and /etc/default/rudder-jetty 
  - reads jetty config /opt/rudder/jetty/etc (distribution base) and /opt/rudder/etc/rudder-jetty-base/etc (specific overrides)
  - starts jetty server
  - reads config & load classes from war in  /opt/rudder/share/webapps/
  - start wars according to config
  - Rudder war starts (java servlet standard, init from WEB-INF/web.xml)
    - HTTP authentication filters (spring-security)
      - prints log "Rudder version xxxx / build xxxx"
    - Rudder main application starts
      - Initialize logging 
      - Read configuration properties from /opt/rudder/etc/rudder-web.properties
        - print logs about all property read
      - Initialize RudderConfig class (rudder business services) by calling `RudderConfig.init()`
        - Services initialized by JVM constructor arg dependency graph for instanciation. More or less:
          - Initialize LDAP & DB connection, Git repository
            - Early bootstrap checks
              - migrate PostgreSQL schema
              - change LDAP structure
              - ...
          - Initialize other service. 
        - `endconfig` bootstrap checks: 
          - consistency: connection up, DIT OK, ....
          - one time init: first time config export in git, ...
          - migration:  internal format,...
          - boot actions: create API system token, trigger policy update (delayed), ...
      - END `RudderConfig.init()`
      - Initialize `Boot` class (rudder web application) and call `Boot.boot()`
        - initialize general web properties (file size limit, etc)
        - publish API access
        - publish web site map (menu, authorization, etc)
        - init plugins, update what needs to be up-to-date (authentication, ...)
        - *** **around here, user can log in** ***
        - init inventory processing, other things
        - publish event "rudder started"
        - publish log "INFO  application 
        - Application Rudder started"
        - start policy generation
      - END `Boot.boot()` 
    - END Rudder main application
  - END Rudder war
  - publish log "INFO:oejs.Server:main: Started @17275ms"
- END Jetty starts

# Service instantiation

The root idea of service initialization in Rudder is that we let `scalac` and the 
`JVM` manage it for us. 

For that end, we use the following rules: 
- All service instantiation is done in a **method**. If it's not the case, then
  deadlocks ensue. That method is `RudderConfigInit.init()`. 
- In that method, all services are `lazy val` so that dependency graph is 
  done need, based on parameter order, and not by order in the source file. All
  these names and services are private, we can easily change them with caring for
  API breakage in plugins for example. 
- That method returns a big case class with all public service instances. 
- `RudderConfig` is an object, that exposes all public services as `val` with the
  trait signature (code to interface rule) and their value taken from the returned
  value of `RudderConfigInit.init()`. Here, all name and signature define a 
  public API, and we can't change anything without dealing with the migration cost
  everywhere. 

# Bootstrap checks

## Post-`RudderConfig.init()` checks
Bootstrap checks are sequence of things to do at specific time, more prominently 
once all Rudder services are correctly instantiated, but before other parts 
of Rudder are started. They are a good place to fail Rudder boot process is 
something is not how it should. 

All these checks are located in package `bootstrap.liftweb.checks.endconfig`.

The entry point is exposed out of `RudderConfig.init()` with `rci.allBootstrapChecks.checks()`.

## Early checks and DB schema migration

We also have some specialized sequence of bootstrap check that need to happen
early in the process, as soon as a specific, central service is ready. 
In particular, this need to be done for database related services, where
schema migration can happen. 

More precisely: 

-  `bootstrap.liftweb.checks.earlyconfig.db` contains checks that need to be done
    as soon as `Doobie` service (PostgreSQL connection) is ready. They are not
    exposed out of that service, and so they can't use any other services. 
- `bootstrap.liftweb.checks.earlyconfig.ldap` contains checks that need to be done
    as soon as `LDAPConnectionProvider[RwLDAPConnection]` service 
    (LDAP connection) is ready. They are not exposed out of that service, and 
    so they can't use any other services. 

# Plugins

Init of the plugin services is done thruth standard object instanciation of an object inheriting `RudderPluginModule`.  

We are sure that the object is referenced during Rudder boot because we have in `bootstrap.liftweb.Boot` an `initPlugins` method that does just that. 

Plugins relies on class loader introspection with the thin reflection layer provided by https://github.com/ronmamo/reflections.

Rudder checks by reflection for objects that implements `RudderPluginModule` trait and retrieve the object and just expose it. 

That trait has a mandatory attribute `def pluginDef: RudderPluginDef` which stores all plugin information (name, is enabled, etc). It’s the plugin implementation of `RudderPluginDef` that manages the licencing part. 

Reflection only looks for them in packages `"bootstrap.rudder.plugin"`  `"com.normation.plugins"`.

Thats all.
