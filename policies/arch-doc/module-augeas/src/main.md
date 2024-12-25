---
title: "Rudder Augeas module"
author: Alexis Mousset \<amo@rudder.io\>
lang: en
region: US
toc: true
papersize: a4
date: 2024-12-23
bibliography:
  - arch-doc.bib
abstract: TODO

# Use link style & font from the nice "ilm" package
# https://github.com/talal/ilm/blob/main/lib.typ
mainfont: "Libertinus Serif"
header-includes: |
    ```{=typst}
    #show link: it => {
      it
      h(1.6pt)
      super(box(height: 3.8pt, circle(radius: 1.2pt, stroke: 0.7pt + rgb("#993333"))))
    }
    #set heading(numbering: "1.")
    ```
...

# Rudder Augeas module

## Needs

Rudder has supported file editing for a long time, mainly through CFEngine's 
[`edit_line`](https://docs.cfengine.com/docs/3.24/reference-promise-types-files-edit_line.html) bundles. This is a low-level way to edit files, based on regular expressions, and it's not
easy to use.

```cfengine
bundle edit_line inner_bundle
{
  insert_lines:
    "/* This file is maintained by CFEngine */",
    location => first_line;

  replace_patterns:
   # replace shell comments with C comments

   "#(.*)"
      replace_with => C_comment,
     select_region => MySection("New section");
}

body replace_with C_comment
{
  replace_value => "/* $(match.1) */"; # backreference
  occurrences => "all";          # first, last all
}

body select_region MySection(x)
{
  select_start => "\[$(x)\]";
  select_end => "\[.*\]";
}

body location first_line
{
  before_after => "before";
  first_last => "first";
  select_line_matching => ".*";
}
```

We provide a built-in technique (the dreaded [`checkGenericFileContent`](https://github.com/Normation/rudder-techniques/blob/c44f6ebedf760da17b2be0c26470f9fd7e6a5f7b/techniques/fileDistribution/checkGenericFileContent/8.0/checkGenericFileContent.st)), and various methods
to perform file editions.

Even if template-base solutions are usually advised, as they allow maintaining consistency
over nodes and are proven to be way easier to use, the need for file _editions_ persists.
Usual examples include configuring and securing existing infrastructure while trying to limit the
associated risk and cost. It is also common that different parts of a configuration file need to be
maintained by different teams or tools. In this case, file editions are often the only option.

Additionally, Rudder has seen a growing need for auditing configuration files, a use case
not well-supported by the current system. In particular, we need to be able to audit against
an expected state, which may not be precisely defined, but made of a set of rules that should be followed.
But as CFEngine is a configuration management tool, it always expects an exact enforceable expected state.
We also want to be able to report the current state of the system when auditing, also
something not well-supported. As the audit mode in CFEngine is implemented on the basis of a dry-run feature
thought for testing changes, it does not allow collecting information about the current state.

## Why Augeas

[Augeas](https://augeas.net/) is a Linux configuration editing tool and library that allows programs to read and modify
configuration files in their native formats. It parses various config file formats into a tree
structure, enables consistent programmatic edits, and writes changes back while preserving comments and
file structure. It comes with a set of default supported file format, through "lenses", which
cover most needs for Linux system administration.
Its principles are covered in the introduction article [@lutterkortAugeasConfigurationAPI2008] and book [@pinsonAugeasConfigurationAPI2013].

It's commonly used for configuration management tools and system administration scripts.
The most popular use case is in [Puppet](https://forge.puppet.com/modules/puppetlabs/augeas_core/reference) 
where it's used to manage configuration files, but
it is also used by [libguestfs](https://libguestfs.org/), [osquery](https://fleetdm.com/tables/augeas), etc.

Augeas was created in 2007 by Raphaël Pinson, at the beginning of the configuration management tools era, and has seen
intense development for around ten years, mostly by the Puppet developers and community.
Since 2019, it's been maintained, but with mostly bug fixes and no more major features.

The library and CLIs are written in C, and there are existing bindings for various other languages
(Ruby, Python, Perl, etc.)

Even if written with classic configuration management in mind, its flexibility makes it
very capable for addressing audit needs.

A big issue is the current state of Augeas documentation, which is mostly outdated, in split between the 
website, the GitHub repository's wiki and the source code.
As users of the module will need to learn Augeas, we will have to provide a proper documentation source somehow.

documenter raugeas comme un ensemble cohérent
gestion d'erreur, toutes les éditions possibles

Expliquer la magie faite par le module rudder
mais pointer vers doc augeas ???

Regarding Rudder.
Pas de plus haut niveau que ça (openstack api in puppet)
tout est du TEXTE.

Our file management story would, eventually, be re-built on:

* An Augeas module for file editions
* A templating module for whole file content management, supporting Mustache, MiniJinja and Jinja2.
* An `rsync` based solution for file copies

## Module

Once we settle on Augeas to address our file edition needs, and decide to make it available
through ou module API, we still have some open questions.

### Rust bindings

There was an [official Rust bindings repository](https://github.com/hercules-team/rust-augeas), but not maintained and missing important parts.
After enquiring about the current status of the project, we came to the conclusion we had to fork
the project.
The new project is named [`raugeas`](https://github.com/Normation/raugeas), R (for Rust) + Augeas, a common pattern for naming Rust bindings libraries.
We decided not to include it in our existing workflows, but to let it live aside in a dedicated repository,
using GitHub CI, and to publish and use it through the public crates.io repository.
This makes potential external contributions way easier.
We kept the original Apache 2.0 license for the added code, in consistence with other non-Rudder-specific libraries we maintain.

### Packaging

We have several options: build the library statically in the module, use the system library and lenses whenever 
possible, or build dynamically but always embed the library and lenses.
Providing the lenses with Rudder has the advantage of being able to provide a consistent experience across
all supported systems, without having to make special cases to work around bugs.

As we generally build dynamically, and as Augeas depends on `libxml2`, we decided to build the library
dynamically in the module, but to embed the lenses in the Rudder packages, and
skip loading the system lenses.
We also build Augeas on all systems where the last version is not available, also to
ensure a consistent experience, as the lens library is part of the core business of Rudder.

### Performance

Below are some metrics for the different ways to run Augeas, based on `augtool` options, for
the simple task of getting the Augeas version. They show the cost of loading
the tree and lenses.

* `-L`, `--noload` is for skipping loading the tree.
* `-A`, `--noautoload` is for skipping autoloading lenses (and hence the tree).

| Command       |  Mean \[ms\] | Min \[ms\] | Max \[ms\] |       Relative |
|:--------------|-------------:|-----------:|-----------:|---------------:|
| `augtool -LA` |    2.6 ± 0.5 |        1.6 |        4.6 |           1.00 |
| `augtool -L`  |  209.5 ± 5.2 |      200.2 |      221.5 |  80.72 ± 15.16 |
| `augtool`     | 663.0 ± 37.2 |      632.0 |      755.7 | 255.46 ± 49.69 |

Obtained using:

```shell
hyperfine -N  --export-markdown augtool.md
    "augtool -LA get /augeas/version"
    "augtool -L get /augeas/version"
    "augtool get /augeas/version"
```

We can see that loading the full tree is expensive (~ 700ms), and loading the lenses only
is also costly (~ 200ms).
In a Rudder module context, it does not make sense to load the default tree
as we always operate on a specific files we can load on-demand, so we can save some time there.
Regarding the lenses, it's possible to explicitly require a lens name, 
and hence skip loading them. But it is really convenient to have them autoloaded,
and avoid bothering the user with stuff the module can figure out by itself.

But this puts a constraint on how we can use the library, as loading all the lenses
at each call is unacceptable: tens or hundreds of calls to augeas in a policy, which is not uncommon,
would lead to adding tens of seconds to the run time.
We want Rudder to be snappy and keep the "continuous" aspect practical.

## Usage

The main goal it to make the module's API as approchable as possible, and especially focus
on the policy development and debugging use cases.

But we won't try to abstract Augeas. First, because it's already the abstraction we need,
and second, because it's a very powerful tool that we want to expose as much as possible.
So instead of abstracting over Auges, we'll embrace it, extend it with the missing part for audits,
and provide a way to use it in the best conditions.

We'll also build upon the existing Puppet resource, as it's a well-known and well-documented
way to use Augeas.

### User story

_As a system administrator, I want to be able to edit configuration files on my systems, in a reliable and
auditable way, so that I can maintain my infrastructure in a consistent state._

Starting from this, what does the usage workflow look like?

1. Decide exactly which change or audit I want to perform.
2. Translate this into an Raugeas script.




We limit the amount of configuration that can be done in the module.
This makes it simpler to use the same tree across calls.
For example, it is not possible to add additional paths for lenses
to be loaded, or to change the tree root.
We also require a unique target file path.



fournir des methodes idempotente (ensure line present)

Never load the full tree

il faut assi preparer l'experience de dev, avec augtool en companion


ifonly pour l'idempotence avec check intégré

risque de sécurité à parser des trucs ???
TODO fuzzing


auditer avec des templates c'est pas bon.

* configurer mon postfix
* auditer ma conf ssh

augeas vs jinja



