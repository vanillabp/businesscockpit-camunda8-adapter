![VanillaBP](./readme/vanillabp-headline.png)

# VanillaBP Business Cockpit adapter for Camunda 8

[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

Spring Boot [![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fbusinesscockpit-camunda8-adapter%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/businesscockpit-camunda8-adapter/spring-boot-report)<br>
Quarkus [![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fbusinesscockpit-camunda8-adapter%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/businesscockpit-camunda8-adapter/quarkus-report)

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
task listeners, byte for byte, because an application which upgrades has to keep the process version
its workflows are running on ([decision 4](./DECISIONS.md)). The start events are the one deliberate
difference, and [decision 1](./DECISIONS.md) says why.

## What is here today

Four published modules and the machinery around them:

|        Module        |                    Artifact                    |                                                    What is in it                                                    |
|----------------------|------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `core`               | `businesscockpit-camunda8-adapter`             | the listeners written into a model, the workers serving their jobs, and the bridge answering what the cockpit reads |
| `spring-boot`        | `businesscockpit-camunda8-adapter-spring-boot` | the auto-configuration and one bridge bean per configured adapter id                                                |
| `quarkus/runtime`    | `businesscockpit-camunda8-adapter-quarkus`     | the same beans as CDI producers                                                                                     |
| `quarkus/deployment` | `…-quarkus-deployment`                         | the build steps of that extension, and the test booting it against a cluster                                        |

[`GAPS.md`](./GAPS.md) holds the questions the cockpit asks a workflow engine which Camunda 8 has no
answer for on at least one of the release lines built here. What this extension decided to do about
such a gap is in [`DECISIONS.md`](./DECISIONS.md), and each entry names the decision it belongs to.

Beside them stands the machinery. `test-coverage-report` measures each platform separately, and its
`coverage-gate` breaks the build below 85 %. Then there are the four GitHub Actions workflows, the
release-line machinery both of the sections below describe, the formatting rules every VanillaBP
repository shares, and the license and notice files.

What the integration tests of both platforms need comes from two published artifacts rather than from
a module here. The Camunda 8 cluster is `camunda8-adapter-test-support`, published by the VanillaBP
Camunda 8 adapter per release line, so the tests of a line meet the cluster that line was built for.
The cockpit server they report to is `extensions-commons-test-support`, published by the Business
Cockpit and shared with its other extension repositories.

Absent before 8.10, and not as an empty placeholder:

- The `cancel` execution listener at the process. Camunda 8 gained it with 8.10, so a build of that
  line writes it and a cancelled case reaches the cockpit as cancelled. A build of line 8.8 or 8.9
  has nothing to write it with, and a cancelled case stays open in the cockpit with its task list
  emptied - see [decision 11](./DECISIONS.md) and [GAPS.md](./GAPS.md).

## How it is put together

The module layout is the one every VanillaBP adapter repository uses: `core` for everything that
needs neither Spring nor Quarkus, `spring-boot` and `quarkus/runtime` plus `quarkus/deployment` for
the glue that registers the extension with each platform, and `test-coverage-report` for the
per-platform coverage measurement. The artifacts keep the repository name as their prefix, so
`businesscockpit-camunda8-adapter` is the core and `businesscockpit-camunda8-adapter-spring-boot` is
what a Spring Boot application depends on. The prefix keeps a jar of this repository apart from
the jar of the VanillaBP Camunda 8 adapter it plugs into. Version 1 did not make that distinction.

`core` compiles against the cockpit's `extensions-commons`, the extension SPI and the CORE artifact
of the VanillaBP Camunda 8 adapter. That last one names the processing context this extension is
wired with, brings the Camunda client, and hands out the client of each configured adapter id. No
platform integration is a dependency of `core`, and that is deliberate: a module which compiled
against one would stop proving that it needs neither.

Which cluster a pipeline call belongs to is the adapter's word. `Camunda8ProcessingContext` names the
adapter id and the workflow module of the run, so the workers of a module are opened per cluster, and the
identifiers in a model are read as the ones that cluster will know. Neither is worked out here by trying
what every configured adapter would call a process.

What the extension writes into a model, what its workers do with the jobs that produces and what it
reads back is documented for users in the
[wiki](https://github.com/vanillabp/businesscockpit-camunda8-adapter/wiki).

## Release lines

A Camunda 8 cluster upgrade is expensive, and it costs the organization more than it costs the
technology, so users sit on different minors at the same time. Camunda promises a client against
clusters of its own version and newer, and says nothing about the other direction. So the client a
build was compiled against IS the lowest cluster version that build accepts. One artifact cannot
serve every minor. That is why the
[VanillaBP Camunda 8 adapter](https://github.com/vanillabp/camunda8-adapter) is published once per
Camunda 8 minor, with the minor in the version.

This repository follows that scheme, and it has to. The extension compiles against the adapter's
core and gets the Camunda client through it, so a build of this repository inherits the minimum
cluster version of the adapter build it was compiled against. A line here means the same thing it
means there:

|   Channel   |        Version        | Camunda 8 adapter line |   Client pin    | Tested against  |
|-------------|-----------------------|------------------------|-----------------|-----------------|
| previous GA | `0.x.y-8.8`           | `-8.8`                 | `8.8.39`        | `8.8.39`        |
| current GA  | `0.x.y-8.9`           | `-8.9`                 | `8.9.21`        | `8.9.21`        |
| preview     | `0.x.y-8.10-alpha<n>` | `-8.10-alpha<n>`       | `8.10.0-alpha5` | `8.10.0-alpha5` |

The client pins in the POM follow `vanillabp/camunda8-adapter` rather than the newest release
Camunda offers, and they move when that repository moves. A cluster version appears in the last
column only once a build of that line has been proven against it. The preview line is proven
without the tests which need a user task, for the reason the next section but one gives.

The integration tests do not start the cluster this POM pins. The image comes from
`camunda8-adapter-test-support`: the Camunda 8 adapter filters its own pin into
`camunda8-cluster.properties` while building that artifact, and `ClusterUnderTest` reads the image
from there. So a pin here says what the code is compiled against, and the adapter says what it is
run against. Both say the same thing for as long as the two pins are equal, which is the whole
reason the pins here are raised whenever that repository raises one, see
[decision 10](./DECISIONS.md).

While they are not equal, a line runs a client older than the cluster answering it. Nothing fails
over that. The literals the newer cluster gained arrive as `UNKNOWN_ENUM_VALUE`, and a fallback
nobody chose reads like one somebody did. So the nightly matrix is what says it: every line it
builds prints the image the adapter named beside its own pin, and the line is red while the two
differ. The night builds every line, so every line is covered.

A client downgrade inside a line is not supported. Camunda adds enum literals and interface
methods in patch releases and does not count that as breaking, so a build compiled against
`8.9.21` can call a method `8.9.11` never had. Going back therefore fails at runtime and nowhere
near the downgrade. Move the pin forward instead, or move to the line whose pin you want.

The same habit is why a moved pin is read rather than trusted. Every pull request which raises one
gets a comment naming the enum literals and the interface methods the new version added, and on a
GA line it also gets a red check. That check is `client-api-changes.yaml` of
`vanillabp/camunda8-adapter`, called from `.github/workflows/client-api-changes.yaml` here, so both
repositories answer the same way.

The preview line is built every night, without the tests which need a user task. The REST gateway
of `camunda/camunda:8.10.0-alpha5` throws a `NullPointerException` while it converts a task
listener job whose event carries no user task action, and it drops the whole activate-jobs batch
(`camunda/camunda#58193`). The two events without an action are `creating` and `canceling`, so a
Camunda-managed user task is never finished being created on that alpha. The tests which need one
carry the tag `user-task-listener-jobs`, and the `line-8.10` profile excludes it. Nineteen of the
thirty integration tests carry it. The eleven which run prove the deployment, the start of a case,
the execution listeners, the search in the secondary storage, and the two things this line alone
has, the cancel listener of the process and the job lease. A red preview line holds up no pull
request and no release, see [decision 14](./DECISIONS.md). The VanillaBP Camunda 8 adapter runs its
8.10 line the same way, with the same tag.

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

Every line reads the same snapshot of the Camunda 8 adapter, so a local repository holds one
adapter for all of them and the last install into it wins. A line built against an adapter
someone installed by hand therefore takes `-Padapter-built-locally`, which asks for
`2.0.0-<line>-SNAPSHOT`, the version the nightly matrix builds that adapter under.
[`AGENTS.md`](./AGENTS.md) says what it costs to forget it.

Switching a line always needs `clean`, and the CI does it that way. Classes compiled against one
Camunda client are binary compatible with no other one. A method the newer model library inherits
from a type the older one does not have at all is called through the owner the compiler saw. So a
stale `target/` fails at runtime with a `NoClassDefFoundError` rather than at compile time.
Building the same line again is fine.

The version is `${revision}`, resolved into the published POMs by `flatten-maven-plugin`, so the
same commit produces every line and a fix exists on every line the moment it is committed.

The same plugin writes the POM an application reads, and that POM has to stand on its own. It is
flattened in the `oss` mode: every version resolved, no parent, no `dependencyManagement`, no
properties, no profiles. A property would be no help in it. An application activates none of our
profiles, so a published POM still naming `${camunda8.version}` reads the default of the file,
which is the current GA line, on every line. That is what every line did until 2026-09-20. Nobody
had looked, because the source POM reads right and a build of a line uses the source POM. The
installed POM of `0.9.0-8.8-SNAPSHOT` named `io.camunda:camunda-client-java` with no version at
all and kept its parent, and that parent said `${camunda8.version.line-8.9}`. An application on
the 8.8 line would have been handed the 8.9 client, and the client a build was compiled against
is the lowest cluster version it accepts, so the line promised a cluster and then took it away.
No line is released yet, so no user ever read such a POM. `Camunda8PublishedPomTest` reads the
published POM on every line since and holds the client in it against the client the build was
compiled against.

The same POM also names the repository, and it names the root of it in every artifact. Maven
would append the module path to that address and to all three `scm` elements, which points at a
page nobody can open; four `child.*.inherit.append.path` attributes in the parent switch it off.
Where we deploy is left out of the published POM, because it is nothing a consumer can use. Both
are part of what the test reads.

Nothing else of ours reaches an application either, and that is why the parent is dropped rather
than corrected. What this repository pins for its own build is chosen for the newest line, and a
user of the oldest line has no reason to be handed it. See [decision 12](./DECISIONS.md).

Code that cannot be shared goes into a per-line source directory added by `build-helper-maven-plugin`,
`src/main/java-line-<id>` and `src/test/java-line-<id>`. Only two kinds of code belong there: code
that cannot compile against every supported client, and code that uses something only a newer
cluster has.

Two of those exist today. In both of them line 8.8 asks the cluster for what the newer clients put
into the job: which workflow a job belongs to when its process was called by another one
(`Camunda8CallHierarchy`), and the business key a workflow is shown under (`Camunda8BusinessIds`).
What a report says is the same on every line. What it costs is not. How long line 8.8 waits for the
cluster's answer is the adapter's `vanillabp.adapters.<id>.workflow-visibility-timeout`, the same key
the adapter waits out for the same storage. See [decision 7](./DECISIONS.md).

### What an application pins itself

The published POMs carry no `dependencyManagement`, so what this repository pins for its own
build reaches nobody. An application resolves each dependency from its own platform BOM, or from
the Camunda client when nothing of its own manages it. There is one case where that is not
enough.

Protobuf refuses a runtime older than the generated code linked against it, and the Camunda
client brings generated code. These are the numbers involved, read on 2026-09-23 from the client
POM of each line and from the two platform BOMs this repository builds against:

| Line |   Client pin    | Its gencode | Spring Boot 4.1.1 manages | Quarkus 3.39.3 manages |
|------|-----------------|-------------|---------------------------|------------------------|
| 8.8  | `8.8.39`        | `4.31.1`    | `4.35.1`                  | `4.35.0`               |
| 8.9  | `8.9.21`        | `4.33.6`    | `4.35.1`                  | `4.35.0`               |
| 8.10 | `8.10.0-alpha5` | `4.36.0`    | `4.35.1`                  | `4.35.0`               |

An imported BOM beats a transitive version. So on both GA lines the application runs a protobuf
newer than its client asks for, which is what protobuf allows. On the preview line both platforms
hand it an older one. The VanillaBP Camunda 8 adapter asks protobuf that question while it builds
its client, so the boot stops there and the message names both versions and the entry to add.
Before that check the answer came at the first command touching the protocol, an
`ExceptionInInitializerError` out of whichever part of the application had sent it.

An application on the preview line therefore pins `protobuf-java` itself, to the gencode of that
line's client, in its own `dependencyManagement` and above the platform BOM. Nothing published
here can do it for the application: its own BOM wins over anything arriving through us. The GA
lines need no pin. Read the number of a client rather than guess it:

```bash
mvn -Pline-8.10 -pl spring-boot dependency:tree -Dincludes=com.google.protobuf
```

### What proves a line

Every line is built and tested once a night by `line-matrix.yaml`, which reads the live lines out of
the `line-*` profiles, so the matrix cannot fall behind the build. A pull request builds the current
GA line alone, because the Camunda 8 integration tests are the slowest thing here and a story would
otherwise pay for every line. When a pull request moves a pin, the matrix runs on that pull request
as well: a pin of line 8.8 is not compiled by a build of line 8.9, so it would otherwise be merged
unbuilt.

A line of this repository needs the adapter of the same line underneath it, and the VanillaBP
Camunda 8 adapter publishes one snapshot, built against the current GA client. So the nightly job
checks that adapter out and compiles the modules this repository consumes, under the version string
of the line it is building. That is a compile and no test run: what the adapter itself does is
proven by the adapter's own matrix. Once the adapter publishes a snapshot per line, the three
`camunda8-adapter.version.line-*` properties point at those and the step falls away.

The cluster a line meets is the adapter's word rather than ours. Its `test-support` artifact carries
the image and the secondary storage of its line, and the tests here start what that artifact names,
which is why line 8.8 runs with an Elasticsearch beside the cluster and the newer lines do not. The
last step of a line reads that image back and holds it against the client this POM pins for the
same line, so a pin which fell behind the adapter's turns the line red instead of staying
invisible.

What no cluster can answer is whether the lines offer the same thing. `bin/api-identity.sh` builds
every line and compares the public API of every JAR with `javap`, and the job `api-identity` runs it
on every pull request. A line may differ in what it does, never in what it offers. A user must never
read the version suffix to find out which methods exist.

An old line therefore holds up a release and not a pull request, and two rules pay for that. A
release runs only while every current line is green in the full matrix: `release.yaml` calls
`line-matrix.yaml` as its first job and publishes nothing until it is green. The preview line is
left out of that gate the same way the night leaves it out, and it is released all the same,
because its version says alpha. And a line which breaks in the night opens a GitHub issue, written
by `release-lines-issue.yaml` and labelled `release-lines`, with the line, the commit and what the
log said. A line which is still red the next night gets a comment on that issue rather than a
second issue. See [decision 9](./DECISIONS.md).

### Version ordering, and why Renovate does not use maven versioning

Maven orders the suffix as an addition rather than as a pre-release, and that is what makes the
suffix usable at all: `0.9.0-8.8 > 0.9.0`, and `0.9.0-8.9 < 0.9.0-8.10` numerically rather than
lexically. One
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

That runs everything, the integration tests included. Those start a Camunda 8 cluster and an
Elasticsearch beside it through Testcontainers, so a build needs Docker and takes a few minutes.
Without Docker the integration tests skip themselves and the unit tests still run.

Leaving the integration tests out takes `-DskipITs`. `-DskipTests` on its own skips the unit
tests and starts them, because Failsafe 3.6.0 no longer reads that property.

Snapshots are published to GitHub Packages by the pipeline described below, and releases go to
Maven Central under the groupId `io.vanillabp.businesscockpit`, like the rest of the Business
Cockpit.

## What CI runs

`build.yaml` builds and tests a pull request, on the current GA line alone, which includes the
integration tests against a cluster of that line's client. It runs in a group per pull request, so
an open pull request never takes the waiting run of another one out. Three checks beside it are
about the lines: `api-identity` proves that every line offers the same API, `renovate-configuration`
validates the Renovate files and runs the gating check, and `pin-change` starts the whole line
matrix when a pull request moves a pin, because a pin of another line is not compiled by a build of
the current one.
`line-matrix.yaml` builds and tests every line once a night, and it is what the section
[Release lines](#release-lines) is proven by.
`deploy-to-github-packages.yaml` publishes the snapshot, and only for a push to `main`. The
snapshot artifacts share their coordinates, so what the other repositories compile against has to
be what `main` holds. It runs in a group of its own, one publish at a time, and a publish which is
already running is never cancelled, because two runs publishing at the same time would overwrite
each other. `release.yaml` is started by hand, reads the live lines out of the `line-*` profiles so
it cannot fall behind the build, and publishes each of them to Maven Central from the same commit.
It deploys no snapshot, so it can run beside a publish.

`deploy-to-github-packages.yaml` also publishes the two coverage reports to GitHub Pages, which is
what the badges at the top of this page link to. The number is the current GA line and nothing
else: the publish names no `line-*` profile, and a build without one is line 8.9. The nightly
matrix builds and tests the other lines, but it writes no report.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by
[Phactum](https://www.phactum.at) with the intention of giving back to the community as it has
benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
