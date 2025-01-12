---
title: "Rudder Linux agent logger v4.1"
author: Alexis Mousset
abstract: This document describes the current behavior of the execution model of our CFEngine policies, and the
  evolutions in the "logger v4.1" project for Rudder 8.3.
...

# Introduction

Making CFEngine execute all parts of the policies and send all reports has been a constant fight in Rudder development.
This is due to the way we bend the execution model, and to our custom reporting tooling. This document describes a new
iteration of workarounds, hopefully more reliably than previous ones.

# Context: CFEngine, ncf & Rudder

## The CFEngine runtime model

```cfengine
bundle agent do_things {
  vars:
    "name" string => "value";

  methods:
    "placeholder" usebundle => other_bundle("${name}");
}
```

The CFEngine policies' structure is declarative, and made of promises and bundles. The promises are the leaves, and
control individual items on the system. The bundles are collections of promises of different types. Bundle can also
reference other bundles (as `methods` promises), leading to a tree structure made of bundles, starting from a `main`
bundle (or a main sequence of bundles), with promises as leaves. Even though bundle call in `methods` promises look a
lot like function, they do not behave as such. They are to be seen as persistent objects, that can be re-evaluated
several times. The agent travels across the policies, evaluating what is ready, and does it three times in each bundle.
This
way it is expected to reach the target state, i.e., to converge.

Evaluation of the bundles themselves follow predefined steps: a pre-evaluation, then three passes of full evaluation.
The passes evaluate promises types in a hard-coded order, called _normal ordering_. A crucial behavior of CFEngine is
that a promise will only be evaluated once. If the agent encounters the same promise twice, it will skip it the second
time.

## The Rudder runtime model - `ncf`

In the early days, Rudder techniques used to be implemented in "standard" CFEngine. To allow the users to build their
own policies, a new type of techniques was introduced, `ncf`/the technique editor. The model used in `ncf`, the library,
which is the preferred way of creating policies, it to use single purpose bundles, called methods like functions, and
assemble the policies by listing calls to methods bundles in what is called a technique (basically, a bundle containing
only methods promises).

This model has several drawbacks:

* It breaks the evaluation model of CFEngine, where the policies are declarative, by forcing an imperative model. We
  hence have to ensure unicity of all bundle parameters everywhere to make sure nothing is skipped.
* The function API is unsuited to configuration management: the resources and attributes model is better.

But it has the big quality of providing a simpler representation of configuration, allowing the creation of the visual
editor, and the YAML technique format.

## `ncf` reporting

One of the big problems is that we need to carry a lot of data to be able to make the expected reporting. And this data
is not passed by CFEngine in its promises, so we need to carry it in the global execution context. We do so by storing
the data in a `report_data` storage bundle, and we provide methods to update the context before calling bundles that
will output reports. This technique is also used for storing other important context, like the dry-run
information (providing the policy modes feature).

### Logger v1

It was a dark age, with CSV files containing information about reports to be made. _No further explanation shall be
made._

### Logger v2

It relied on what is now called the `old_class_prefix`, i.e., a class uses for outcomes in original `ncf`.
It is not used anymore.

### Logger v3 (Rudder 4.3+)

Adds a new class prefix that contains all parameter values., named `class_prefix`. The old class prefix is kept (as it
is used in the techniques themselves as conditions), under the `old_class_prefix` name. It is also used as fallback is
the new class prefix is too long (as conditions are limited to 4k characters).

### Logger v4 (Rudder 7.1+)

This logger introduced in Rudder 7.1 is centered around the concept of `report_id`. It also embraces the `report_data`
storage, with context setting APIs for providing the required user information (about techniques, directives, etc.) and
keeping the direct reporting methods simpler. The report ID has the advantage of being opaque and independent of
business data.

The API made of:

* reporting context bundles, to be called before calling the method itself,
* execution context bundles to be called inside the method, when calling sub-methods,
* a log bundle called once in each method, at the end.

There is one remaining problem with it: iterators breaks the expectation that `report_id` is unique, and merges the
outcome classes of different calls to the same method.

# Logger v4.1

The goal of the 4.1 logger is to address the iterator problem with minimal impact, and to document things (at last). It
is made possible by the Rudder 8.2 server version, which enforces the usage of `rudderc` to generate techniques. We can
encode some new invariants reliably in the generated code, and fix the issue without too much trouble.

We have three very distinct contexts:

* The library, `ncf`, context. Code is static with methods as entry points, and reporting and outcome classes on
  `old_class_prefix` (and`report_id` for v4+ methods).
* The generated user code, from `rudderc`. It only calls methods (and soon modules).
* The legacy techniques, calling the library by hand, and sometimes using pure CFEngine too.

When calling a method, we need to give it enough unicity through context, and the library code also needs to provide
enough unicity for the internal operations.

## User code

The `report_id` is encoded directly in the generated bundle names. What is needed to be able to ensure we properly run
all methods with iterator parameters is enough uniqueness. To consider the most "extreme" case, we can configure a
method to take an iterator as parameter, and make this iterator contain the same value several times. This will lead to
calling the same method, with the exact same parameters (`args`, `report_id`, etc.). The call stack is also identical,
and there is no observable difference from inside the evaluation context. But we still want the outcome to be somewhat
different, so we will need some form of non-determinism. The two main options are some randomness (like we do with UUIDs
for reporting), or a kind of clock, measuring time advancement. This source needs to provide a unique value, more than
for each method bundle, for each method bundle call. The bright side is that once we have it (and if it is globally
unique for a run), we can remove other unique tokens used in the generated techniques. As we want to keep it as simple
and efficient as possible, let's rule out randomness and create our own "clock" by counting the entries in one of the
bundles generated by `rudderc` (i.e., the techniques themselves, plus on for each called method, allowing iterating over
the parameters). As each iterator iteration will re-enter the bundle, we will get an incremented counter and a unique
value. Everything can be done in the generated code, we only need to initialize the value to zero at some point.

We chose to inline the value increment as opposed to creating a dedicated method in the library. This has two goals:
keeping the performance impact low, and more importantly, guarantee that the value will stay the same for the whole
method run, as we can enforce the increment to be the first promise to run easily. If it was a method, the value would
change in the middle of the bundle execution. Inside a bundle, we need to keep a second "uniqueness" factor, as the
global bundle index can stay the same. As we work with generated techniques, we can keep a simple counter of promises in
the promiser in each bundle.

We end up with a light system of two integer coordinates identifying each promise uniquely: spatially by its position
inside the bundle
and by `report_id` as it is part of the bundle name, and in time by its "bundle call instance" index.
A nice property of this new set of unification IDs is that contrary to previous versions, it is totally separate from
business data. This prevents issues with unexpected data (too long value, etc.)

```
   vars:
     "report_data.index" int => int(eval("${report_data.index}+1", "math", "infix")),
                      unless => "rudder_increment_guard";
     "local_index"       int => ${report_data.index},
                      unless => "rudder_increment_guard";
 
   classes:
     "rudder_increment_guard" expression => "any";

```

We create a local snapshot of the value for local usage (as the index will be increment by called methods in
techniques). The counter could be internal to the bundle, but as we also need to pass it to the library for proper
iterator handling, we have to make it global. Finally, due to the pre-evaluation of the policies, the counter increments
two by two.

To sum things up, the user code guarantees:

* That every time we change context to the lib, the `report_data.index` variable will have a different value,
* All iterators have evaluated in a previous level.

## Library code

The main goal here is to avoid having to modify all methods and make a fix contained in the logger v4 implementation. We
actually only need to make `method_id`, used everywhere as a uniqueness source in the logger v4 methods, more unique to
allow running it in the case of iterators. This can be done by adding the current global bundle index as suffix to the
existing value. The method stack is not affected, so the classes copies are not affected either.

We have a few constraints:

* We can't add parameters to methods
* We want to avoid having to modify all methods as much as possible.

### Reporting

The reporting is now based on three main context variables:

* `report_data.report_id`: Fixed for the whole method call. Unique to each component, common when parameters are
  iterators. Target for reporting, the outcome classes must be defined on it.
* `report_data.index`: Set by the generated user code. Stays identical until control is given back to the user code. Is
  guaranteed to be different at each context switch.
* `report_data.method_id`: Unique to the current method instance.
    * Contains `report_id + index + ${method_call_stack}`
        * The `report_id` and `index` never change inside the lib code.
        * Note: the names of the methods are arbitrary and set by the policy developer.
    * Used to copy classes between inner and outer methods.

### Report ID

The `report_id` is a UUID generated on the serveur side for each component (= each method call).
It is unique and
allows matching the received reports against what is expected. It solves a lot of issues, but leaves two problems in
logger v4.0:

* It breaks with iterators, as the web app does not know about them, and iterated methods will report several times for
  the same `report_id`.
* We can't just report on it (i.e., make a report based on classes defined with the `report_id` prefix) as it would
  break
  when calling methods from other methods.
    * We need to construct intermediary class prefixes for inner methods. Here comes the `method_id`.

### Method ID

The method ID is used to overcome the `report_id`'s limitations. It starts with the `report_id` value, and is expanded
with additional unicity and specificity values.

Before calling a method, we set it to `${report_id}_${report_data.index}` to ensure unicity over method parameters,
including iterators. Then we need unicity for what happens next. In addition to unicity, we need predictability as we
need to get the classes back. To achieve this, we stack the method names in the `method_id`, like
`${report_id}_${index}_method1_method2`. There was a directive ID in v4.0, but the index removes the need for it (and
improves separation with business data). The method ID has two goals:

* Provide unicity to ensure all bundle calls are honored
* Provide a stable base for passing outcome classes
    * In order for it to stay stable, the library code MUST NOT increment the index, and leave it only to the user code.

# Future

Once all methods use the v4 logger, we will be able to start relying on the `report_id` for outcome classes, removing
the dependency on the class parameter value.

# Annex: Using the v4.1 logger

This section is a developer manual for the v4.1 logger. It explains how to port methods to using it.

## Simple method

A simple method looks like:

```cfengine
bundle agent create_a_file(id) {
  vars:
      "flag_file"    string => "/path/to/file";
      "class_prefix" string => canonify("rudder_inventory_trigger_${id}");

  files:
      "${flag_file}"
        create        => "true",
        classes       => classes_generic_two("${report_data.method_id}", "${class_prefix}");

  methods:
      "${report_data.method_id}" usebundle => log_rudder_v4("${id}", "Create a file", "");
}
```

The only required things are:

* A class prefix definition, matching what is called `old_class_prefix` in logger v3, i.e., the class prefix of the
  method plus the value of the class parameters.
    * It is required as it is how methods are linked through the technique editor,
    * Once we have a v4 logger everywhere, we will be able to rely on  `report_id` instead.
* A call to `log_rudder_v4` at the end of the method. It expects the outcome classes to be defined on (`method_id`).

Note: You should not use `report_id` directly as it prevents reusing the method from another method. The class copy from
`method_id` to `report_id` is handled by the logger itself.

## Method chaining

A method that just calls another one works like this:

```cfengine
bundle agent package_present(name, version, architecture, provider) {
  vars:
      "class_prefix" string => "package_present_${name}";

  classes:
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";      

  methods:
    pass3::
      "${report_data.method_id}" usebundle => call_method("ncf_package");
      "${report_data.method_id}" usebundle => ncf_package("${name}", "${version}", "${architecture}", "${provider}", "present", "");
      "${report_data.method_id}" usebundle => call_method_classes("${class_prefix}");
      "${report_data.method_id}" usebundle => call_method_classes_caller;
      "${report_data.method_id}" usebundle => call_method_end("ncf_package");
      "${report_data.method_id}" usebundle => log_rudder_v4("${name}", "${ncf_package.message}", "");
}
```

It requires:

* The `class_prefix` as usual.
* Calling a sub-method:
    * `call_method` will set the right context:
        * Disable reporting (after storing the value)
        * Push the passed method name to the `method_id` class.
        * The name passed to this method is by convention the name of the called bundle. It could still be different,
          for example, in case you need to cal the same bundle several times.
    * Then call the method normally.
    * Call `call_method_classes_caller` to copy the outcome classes of the current method to its parent.
        * We do this to make the outcome of the inner method the outcome of the current one.
    * End the call with `call_method_end`
        * Put the reporting back in its previous state.
        * Pop the method name from `method_id`
    * End the method call with a normal call to `log_rudder_v4`.

## Complex method chaining

A method that calls another method and uses its output uses the same process as described above, but without calling
`call_method_classes_caller`, and by manually copying or defined the wanted classes.
