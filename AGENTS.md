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
