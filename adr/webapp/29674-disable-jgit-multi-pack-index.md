# Disable the git multi-pack-index in JGit

* Status: accepted
* Deciders: FAR
* Date: 2026-08-29

## Context

Rudder 9.2 upgraded JGit from 7.5 to 7.7, which [integrated multi-pack-index support into `PackDirectory`](https://github.com/eclipse-jgit/jgit/wiki/New-and-Noteworthy-7.7). JGit now reads `.git/objects/pack/multi-pack-index` when `core.multiPackIndex` is true, and [true is its default](https://github.com/eclipse-jgit/jgit/blob/v7.7.0.202606012155-r/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/file/PackDirectory.java#L139-L141). JGit never writes that index, but the git CLI does on `git gc` and `git maintenance`, and `/var/rudder/configuration-repository` is an ordinary repository that administrators run git commands on.

When the index is present, JGit [drops from its pack list every pack the index covers and puts the index in their place](https://github.com/eclipse-jgit/jgit/blob/v7.7.0.202606012155-r/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/file/PackDirectory.java#L571-L583). The index becomes the only route to those objects: [what it does not resolve is reported as absent](https://github.com/eclipse-jgit/jgit/blob/v7.7.0.202606012155-r/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/file/PackMidx.java#L251-L259), with no second chance on the packs themselves. One faulty or discarded index entry therefore hides the content of every pack it covers.

That is how it showed up in 9.2 ([#29674](https://issues.rudder.io/issues/29674)): policy generations failed intermittently with `MissingObjectException: Missing tree <id>`, on objects that `git cat-file` resolved without trouble and that `git fsck` reported as sound, and the next generation succeeded. 9.1 and its JGit 7.5 never showed it, and setting `core.multiPackIndex` to false makes it stop.

## Decision

Set `core.multiPackIndex` to false in the configuration of every git repository that Rudder opens, in `GitRepositoryProviderImpl`. Since the option is read when the object database is built, the repository is reopened once the value has been written.

## Consequences

* Our repositories are small enough that we lose nothing by not using that index. Git keeps the `.idx` files in the pack directory precisely so that it can be ignored ["without any loss of information"](https://git-scm.com/docs/multi-pack-index).
* The option is written in `.git/config`, so git commands run by an administrator ignore the index too. `git gc` and `git maintenance` may still write the file; it is simply never used.
* Should a future JGit version keep the covered packs usable when the index fails to answer, this decision can be revisited.
