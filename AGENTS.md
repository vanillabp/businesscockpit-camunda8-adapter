# Working on businesscockpit-camunda8-adapter

The VanillaBP Business Cockpit integration for Camunda 8, built as an extension of VanillaBP
Version 2, on Spring Boot and on Quarkus.

Read [`README.md`](./README.md) first: it says what is here today, what arrives with the extension
work and why, and how to build. The release lines are not optional reading before you
touch the POM: this repository is published once per Camunda 8 minor, and the section of the README
that explains it says which parts move when a line rotates.

## Before your first build

`mvn install` builds the current GA line and runs everything, the integration tests included.
Three things about a local build here have each cost somebody a run already.

### The Camunda 8 adapter in your local repository belongs to one line

This extension compiles against the VanillaBP Camunda 8 adapter, and that adapter is not
released per line yet. Every line of this repository asks for the same `2.0.0-SNAPSHOT` of it,
so your local repository holds one adapter for all lines. The last `mvn install` in the
adapter's clone decides which line is in it, and `~/.m2` is usually shared: between agents
working at the same time, and between containers which mount the same volume.

Going wrong looks like this. The build stops with a `cannot find symbol` on a class of the
adapter, because a per-line class of your line is missing from the jar somebody else installed.
Or it does not stop at all, and the integration tests run the client of one line against the
cluster of another. On 2026-09-20 that was 16 red tests out of 20 and 38 minutes, and every
error read like an error of the cockpit, down to an HTTP 400 from the search API.

So build the adapter for the line you want, and give that build the version of the line:

```bash
# in the clone of vanillabp/camunda8-adapter
mvn -Pline-8.8 -Drevision=2.0.0-8.8-SNAPSHOT -Dmaven.test.skip=true \
  -pl core,spring-boot,quarkus/runtime,quarkus/deployment,test-support -am \
  clean install

# here
mvn -Pline-8.8,adapter-built-locally clean install
```

The profile of the line is not optional in the adapter's build. The adapter keeps the code that
cannot compile against every client in `src/main/java-line-<id>`, and a build without a profile
takes the directory of the current GA line.

`adapter-built-locally` is a profile of this repository. It asks for `2.0.0-<line>-SNAPSHOT`
instead of the shared snapshot, which is the version the nightly matrix builds the adapter
under. It is what makes the two builds meet: with it a line gets the adapter that was built for
that line, or the build stops because nobody built one.

### Skipping the tests takes `-DskipITs`

`-DskipTests` skips the unit tests and starts the integration tests. Failsafe 3.6.0 does not
read that property any more, and this repository pins 3.6.0, so `mvn install -DskipTests` boots
a Camunda 8 cluster and runs the whole suite. That is half an hour nobody asked for.

Skip both with `-DskipTests -DskipITs`, or with `-Dmaven.test.skip=true` when the tests need
not be compiled either. The nightly matrix uses the second one where it builds the adapter.

### A clone in your container can be behind its main

This one is a note about a container, not a rule of this repository. The clones of the other
VanillaBP repositories are not always at the `main` they publish from. A build against such a
clone fails on a class that `main` no longer has, or does not have yet, and the message points
at code nobody in your branch wrote. Ask the clone you built from before you believe the error:

```bash
git rev-list --left-right --count origin/main...HEAD
```

On the evening of 2026-09-20 no local build under the GA coordinates could be had at all: the
clone of `adapter-platform-integration` was 58 commits behind `main` and failed on
`AdapterPlatformVersion`, and the published snapshots were not reachable from there either.
When that happens, the pull-request build is the proof. A runner clones `main` and reads the
published snapshots, so it has neither problem.

## A deploy is every module or none

`mvn deploy -pl <module>` publishes the modules you name and leaves the rest of the repository as
it was. What stays behind is half of an older build. The parent POM of today can end up beside
module POMs from before the build moved to the flatten plugin's mode `oss`, which still name a
parent and carry dependencies without a version. Neither half looks wrong on its own, and
nothing compares them.
Even `Camunda8PublishedPomTest` reads the POM the running build has just flattened and not the
one lying in the repository, so a green build says nothing about the mixture. Whoever resolves
the artifacts next is the one who finds out, and what they get to see reads like a mistake in
their own code. On 2026-09-20 such a mixture sat in a shared `~/.m2` and cost somebody a quarter
of an hour.

So deploy the whole reactor or nothing. The workflows do it that way already:
`deploy-to-github-packages.yaml` runs `mvn deploy` from the root POM on a push to main, and
`release.yaml` runs `clean deploy` once per line, also from the root. Neither of them passes
`-pl`, so only a deploy somebody types by hand can produce the mixture. If you have to repair a
single module, deploy the whole reactor again instead.

## What a POM hands an application

A tool that only translates our source belongs in scope `provided`, and the scope stands at the
declaration in the module using it. Lombok is such a tool, and an annotation processor is another.
Somebody added one dependency to see their user tasks in the cockpit. Every jar they did not ask
for is one more thing to ship and to answer a CVE report about.

Writing `<optional>true</optional>` in a `dependencyManagement` does not do it. Maven copies a
managed version, scope and exclusions into a dependency and leaves the optional flag behind, so
the POM we publish says nothing at all about that dependency.

The two cases differ, and a sentence written for one does not fit the other. A declaration without
a scope hands the jar to every application, and that is a defect. An `optional` in a
`dependencyManagement` hands out nothing as long as no module declares the dependency. It is a
promise nobody ever keeps, and it breaks on the day the first module declares it. This repository
had the second case for Lombok, and the VanillaBP adapters had the first.

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

## How we write

Most people who read this repository read English as a second language, and so does the
maintainer. Long sentences, rare words and stacked nouns slow them down. Write so that
nobody has to read a sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice.
The common word instead of the rare one: `use` instead of `leverage`, `about` instead of
`regarding`, `so` instead of `consequently`, `has` instead of `possesses`. A technical term
stays a technical term, but say what it means the first time it turns up, and write an
abbreviation out once. If a sentence trips you up when you read it aloud, rewrite it.

This holds for every English text here: the README files, `DECISIONS.md`, this file, the
Javadoc and comments which explain something, and the texts of commits and pull requests.
It holds for the [wiki](https://github.com/vanillabp/businesscockpit-camunda8-adapter/wiki) as
well, because the wiki clone has no `AGENTS.md` of its own.

Nothing a program reads is renamed for the sake of language. Class and method names,
configuration keys, artifact coordinates and the headlines of decision log entries stay as
they are, because code, tests and other repositories point at them.

Before and after, from this repository. Two out of `DECISIONS.md`:

> That listener runs before the variables the workflow was started with exist, so the workflow
> aggregate's id - the one thing every report to the cockpit is about - is not there yet.

became

> That listener runs before the variables the workflow was started with exist. So the workflow
> aggregate's id is not there yet, and every report to the cockpit is about that id.

And:

> A report which cannot be written is a defect somebody has to see, and an incident is how a
> cluster says so; completing the job anyway would let the workflow run on while the cockpit
> loses the event.

became

> A report which cannot be written is a defect somebody has to see, and an incident is how a
> cluster says so. Completing the job anyway would let the workflow run on while the cockpit
> loses the event.

One out of the wiki page about configuration:

> The lock of its workers is `job-timeout`, asked at the workflow module respectively at the
> adapter, because one worker serves a job type across every process using it and a lock resolved
> per process would be several.

became

> The lock of its workers is `job-timeout`. The cockpit asks for it at the workflow module, and
> at the adapter where the module says nothing. One worker serves a job type across every process
> which uses it, so a lock resolved per process would be several locks for one worker.

## Before you open a pull request

A number your branch hands out can be taken by the time you open the pull request. Another branch
was open at the same time and got there first. So check your numbers against `origin/main` and
against every open pull request, before the pull request exists.

It went wrong twice on 2026-09-13 in the cockpit repository: two branches claimed one number,
which had to become 19 and 20, and two more claimed the next, which had to become 21 and 22.
Both times it showed up at the merge, which is the worst moment for it. A merge happens on
GitHub, and a `see decision 21` in a Java file cannot be changed there.

The check:

```bash
bin/check-decision-numbers.sh
```

The script reports and changes nothing. By hand it is:

```bash
git fetch origin
git show origin/main:DECISIONS.md | grep -E '^#+ [0-9]+\. '   # the numbers already taken
gh pr list --state open
gh pr diff <n> | grep -E '^\+#+ [0-9]+\. '                    # for each open pull request
```

`gh pr diff` takes no path argument, so the grep does the filtering.

If your number is taken, your entry gets the next free one, and you correct every citation of it
in the code and in the documentation.

Read each citation before you change it. Not every `see decision <n>` in the branch is about your
decision. A branch can cite a number somebody else handed out long ago, and that citation stays
as it is. A search and replace over the branch turns a right reference into a wrong one.

None of this breaks the rule that a number is never renumbered. That rule is about a merged
number, which a citation in a released artifact points at. Until the pull request is merged,
nothing outside the branch has seen the number, so correcting it costs no more than the branch.

Every other running number is checked the same way. The story prompts are such a series. They are
kept outside this repository, so they are checked where they are kept.

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
