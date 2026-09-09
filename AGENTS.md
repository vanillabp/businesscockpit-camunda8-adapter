# Working on businesscockpit-camunda8-adapter

The VanillaBP Business Cockpit integration for Camunda 8, built as an extension of VanillaBP
Version 2, on Spring Boot and on Quarkus.

Read [`README.md`](./README.md) first: it says what is here today, what arrives with the extension
work and why, and how to build. The release lines are not optional reading before you
touch the POM: this repository is published once per Camunda 8 minor, and the section of the README
that explains it says which parts move when a line rotates.

## This is an extension, not a BPMS adapter

An extension joins the deployment pipeline of the VanillaBP core and implements
`ExtensionWiringService` from `io.vanillabp:vanillabp-extension-spi`. The adapter SPI is what a
BPMS adapter implements, and it has no business in this repository. The pipeline both of them run
in, and the order they run in, is described once for everybody in
[`ADAPTER-AUTHORS.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/ADAPTER-AUTHORS.md)
of the platform repository.

What this extension does with the pipeline belongs to the Business Cockpit, and the cockpit's own
repository is [vanillabp/business-cockpit](https://github.com/vanillabp/business-cockpit). The
platform-neutral half of the extension lives there as `extensions-commons` and is consumed here as
a published artifact, never copied.

## Two names for the same thing

The wiki calls this an adapter. This repository calls it an extension. Both are right, and which
word fits depends on where you stand.

Somebody using the Business Cockpit adds one dependency and sees their user tasks in the cockpit.
From there this is a cockpit adapter, sitting next to the BPMS adapter which runs their workflows.
The VanillaBP core sees something else: a bean which joins its deployment pipeline through
`vanillabp-extension-spi`, and a bean like that is what the core calls an extension.

So the end-user documentation says adapter and never extension. The documentation in this repository
says extension where the core's own term is meant, and adapter where it is about what a user adds to
their application. Say which of the two you mean, rather than assuming the reader knows.

## The decision log is binding

[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places in this repository rely on. It
is the ONLY thing the code is allowed to cite, in the plain greppable form
`see decision 7 in the repository's DECISIONS.md`, and only entries of THIS repository.

Read it before you change behaviour. An entry is not background reading, it is the reason the code
around it looks the way it does, so a change which contradicts one is wrong until the entry says
otherwise.

**A decision is changed or replaced only after asking.** Where your change would make an entry
untrue, stop and put the question to the maintainer before you write the change. If the answer is
yes, the same commit updates the log: the old entry STAYS, marked as superseded and naming the
entry which replaced it, and the new decision takes the next free number. Numbers are never reused
and never renumbered, because a citation in an older release still points at them.

Adding an entry has the same rule. A decision earns a number when several places rely on it and
copying the explanation to each of them would rot; anything smaller is a comment where it belongs,
and anything larger is documentation.

## What code may point at

Nothing which a later change can invalidate without anything noticing: no story or prompt number,
no issue or pull-request number, no chat transcript, no person. Those record a conversation at a
point in time. A decision entry lives next to the code and is overhauled in the same commit, which
is what makes it citable.

Where a name can carry the reason, the name is the better fix. Where it cannot, a comment says why
in its own words, complete where it stands. Only what several places have to carry becomes an entry
in the log.

Commit messages and pull-request descriptions may cite whatever they like. They are records of a
point in time themselves.

## Formatting

`mvn spotless:apply` before every commit. It formats the POMs and the Markdown as well as the Java,
and the build fails on a violation.
