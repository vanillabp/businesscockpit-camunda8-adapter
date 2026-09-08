![VanillaBP](./readme/vanillabp-headline.png)

# VanillaBP Business Cockpit adapter for Camunda 8

[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This repository holds the [VanillaBP Business Cockpit](https://github.com/vanillabp/business-cockpit)
integration for [Camunda 8](https://camunda.com/platform/), built as an extension of
[VanillaBP](https://www.vanillabp.io) Version 2. The cockpit shows user tasks and business cases to
business staff, and to do that it has to learn what happens inside the workflow engine. This
adapter is the half that runs in the workflow application: it injects execution and task listeners
into the deployed models, serves the listener jobs the cluster hands out, asks the application for
the business details of what it saw, and sends the result to the cockpit server.

## Status

The extension is here and it runs, on Spring Boot and on Quarkus, against a real cluster. It builds
on the extension SPI of `adapter-platform-integration` and on the cockpit's `extensions-commons`
module, both of which are still snapshots, so this repository publishes snapshots too.

The Version 1 adapter is still where it always was, as `adapters/camunda8` of the
[business-cockpit](https://github.com/vanillabp/business-cockpit) repository, and it stays there
until the cockpit switches to VanillaBP 2. Nothing of it was moved here: this repository starts from
the extension, so its history never carries the Version 1 shape. What it does carry is Version 1's
task listeners, byte for byte, because an application upgrading has to keep the process version its
workflows are running on ([decision 4](./DECISIONS.md)). The start events are the one deliberate
difference, and [decision 1](./DECISIONS.md) says why.

## What is here today

Four published modules and the machinery around them:

|        Module        |                    Artifact                    |                                                    What is in it                                                    |
|----------------------|------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `core`               | `businesscockpit-camunda8-adapter`             | the listeners written into a model, the workers serving their jobs, and the bridge answering what the cockpit reads |
| `spring-boot`        | `businesscockpit-camunda8-adapter-spring-boot` | the auto-configuration and one bridge bean per configured adapter id                                                |
| `quarkus/runtime`    | `businesscockpit-camunda8-adapter-quarkus`     | the same beans as CDI producers                                                                                     |
| `quarkus/deployment` | `…-quarkus-deployment`                         | the build steps of that extension, and the test booting it against a cluster                                        |

Beside them, `test-coverage-report` measures each platform separately and its `coverage-gate` breaks
the build below 85 %, the three GitHub Actions workflows, the release-line machinery both of the
sections below describe, the formatting rules every VanillaBP repository shares, and the license and
notice files.

One more module is in the tree and in no release: `test-support` holds the cluster the integration
tests of both platforms run against, the file its output is written to and the cockpit server they
report to. A test classpath cannot read another module's test classes, so the alternative was a second
copy of all three.

Deliberately absent, and not as an empty placeholder:

- The nightly build of every release line. It exists in `vanillabp/camunda8-adapter` to run each
  line's integration tests against that line's cluster; the tests here run on the current GA line,
  and a matrix over the other two is worth having once a line other than the current one is
  released.
- The check that the public API is identical on every line. It compares compiled classes of
  different builds, and this repository has one line's worth of them.
- A `canceled` execution listener. Camunda 8 gains it with 8.10, and until then a terminated
  workflow is not reported as such - see [decision 3](./DECISIONS.md).

## How it is put together

The module layout is the one every VanillaBP adapter repository uses: `core` for everything that
needs neither Spring nor Quarkus, `spring-boot` and `quarkus/runtime` plus `quarkus/deployment` for
the glue that registers the extension with each platform, and `test-coverage-report` for the
per-platform coverage measurement. The artifacts keep the repository name as their prefix, so
`businesscockpit-camunda8-adapter` is the core and `businesscockpit-camunda8-adapter-spring-boot` is
what a Spring Boot application depends on. The prefix is what keeps a jar of this repository apart
from the jar of the VanillaBP Camunda 8 adapter it plugs into, which is a distinction Version 1 did
not make.

`core` compiles against the cockpit's `extensions-commons`, the extension SPI and the CORE artifact
of the VanillaBP Camunda 8 adapter - which names the processing context this extension is wired
with, brings the Camunda client, and hands out the client of each configured adapter id. No platform
integration is a dependency of it, deliberately: a module which compiled against one would stop
proving that it needs neither.

Which cluster a pipeline call belongs to is the adapter's word: `Camunda8ProcessingContext` names the
adapter id and the workflow module of the run, so the workers of a module are opened per cluster and the
identifiers in a model are read as that cluster's. Nothing here works either out by trying what every
configured adapter would call a process.

What the extension writes into a model, what its workers do with the jobs that produces and what it
reads back is documented for users in the
[wiki](https://github.com/vanillabp/businesscockpit-camunda8-adapter/wiki).

## Release lines

A Camunda 8 cluster upgrade is expensive, more so organizationally than technically, and users sit
on different minors at the same time. Camunda promises a client against clusters of its own version
and newer and says nothing about the other direction, so the client a build was compiled against IS
the lowest cluster version that build accepts. One artifact cannot serve every minor, which is why
the [VanillaBP Camunda 8 adapter](https://github.com/vanillabp/camunda8-adapter) is published once
per Camunda 8 minor, with the minor in the version.

This repository follows that scheme, and it has to. The extension compiles against the adapter's
core and gets the Camunda client through it, so a build of this repository inherits the minimum
cluster version of the adapter build it was compiled against. A line here means the same thing it
means there:

|   Channel   |        Version        | Camunda 8 adapter line |   Client pin    | Tested against |
|-------------|-----------------------|------------------------|-----------------|----------------|
| previous GA | `0.x.y-8.8`           | `-8.8`                 | `8.8.37`        | not yet        |
| current GA  | `0.x.y-8.9`           | `-8.9`                 | `8.9.18`        | `8.9.18`       |
| preview     | `0.x.y-8.10-alpha<n>` | `-8.10-alpha<n>`       | `8.10.0-alpha4` | not yet        |

The client pins in the POM follow `vanillabp/camunda8-adapter` rather than the newest release
Camunda offers, and they move when that repository moves. A cluster version appears in the last
column only once a build of that line has been proven against it: the integration tests start the
cluster of the client their line pins, so a line's tests meet the oldest cluster its artifacts
accept.

Snapshots have no suffix. Until the first release they are `0.9.0-SNAPSHOT` of the current GA line,
which is what a build without a profile produces, and every line still reads the same
`2.0.0-SNAPSHOT` of the Camunda 8 adapter, because that adapter is not released per line yet
either.

### How the lines are built

Every line is a build variant of this one source tree, not a maintenance branch. A line is a Maven
profile that selects the Camunda 8 adapter and the client pin:

```bash
mvn install                                          # current GA line, 0.9.0-SNAPSHOT
mvn -Pline-8.8 -Drevision=0.9.0-8.8 clean install    # a release of the previous GA line
mvn -Pline-8.10 -Drevision=0.9.0-8.10-alpha1 clean install
```

Switching a line always needs `clean`, and the CI does it that way. Classes compiled against one
Camunda client are binary compatible with no other one: a method the newer model library inherits
from a type the older one does not have at all is called through the owner the compiler saw, so a
stale `target/` fails at runtime with a `NoClassDefFoundError` rather than at compile time.
Building the same line again is fine.

The version is `${revision}`, resolved into the published POMs by `flatten-maven-plugin`, so the
same commit produces every line and a fix exists on every line the moment it is committed. Code
that cannot be shared goes into a per-line source directory added by `build-helper-maven-plugin`,
`src/main/java-line-<id>` and `src/test/java-line-<id>`. Only two kinds of code belong there: code
that cannot compile against every supported client, and code that uses something only a newer
cluster has.

### Version ordering, and why Renovate does not use maven versioning

Maven orders the suffix as an addition rather than as a pre-release, which is what makes it usable
at all: `0.9.0-8.8 > 0.9.0`, and `0.9.0-8.9 < 0.9.0-8.10` numerically rather than lexically. One
comparison goes wrong, and it is the whole risk of a suffix: `0.9.0-8.9 < 0.10.0-8.8`, so "the
newest version" can cross a line boundary. Renovate reads the suffix as a compatibility value
instead of a version part, which fixes exactly that. Extend the preset shipped here to inherit it
in your own application:

```json
{
  "extends": ["github>vanillabp/businesscockpit-camunda8-adapter//renovate/camunda8-lines.json"]
}
```

A pre-release of the preview line is `0.9.0-8.10-alpha1`: the qualifier comes after the line, so
the line always sits in the same place, and Maven sorts `0.9.0-8.10-alpha1 < 0.9.0-8.10-alpha2 <
0.9.0-8.10`.

## Building

```bash
mvn install
```

That runs everything, the integration tests included, and those start a Camunda 8 cluster and an
Elasticsearch beside it through Testcontainers - so a build needs Docker and takes a few minutes.
Without Docker the integration tests skip themselves and the unit tests still run.

Snapshots are published to GitHub Packages by the pipeline described below, and releases go to
Maven Central under the groupId `io.vanillabp.businesscockpit`, like the rest of the Business
Cockpit.

## What CI runs

`build.yaml` builds and tests a pull request, on the current GA line alone, which includes the
integration tests against a cluster of that line's client. `deploy-to-github-packages.yaml`
publishes the snapshot when a branch is pushed. Both run under one concurrency group, queued and
never cancelled, because the snapshot artifacts share their coordinates: two runs publishing at the
same time would overwrite each other, and whoever finished last would decide what the other
repositories compile against. `release.yaml` is started by hand, reads the live lines out of the
`line-*` profiles so it cannot fall behind the build, and publishes each of them to Maven Central
from the same commit.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by
[Phactum](https://www.phactum.at) with the intention of giving back to the community as it has
benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
