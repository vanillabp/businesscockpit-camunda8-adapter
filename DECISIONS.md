# Decision log

Decisions this repository's code points at. A number is handed out once. It is never reused and
never renumbered, so a citation stays resolvable. A decision which is overturned keeps its entry,
and that entry is marked as superseded and names the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it always means an
entry of THIS repository. A decision the platform shares has its own entry in
`adapter-platform-integration`, written from that side. One the Business Cockpit shares has its
entry in `business-cockpit`. A pointer into another repository is the fragile kind this log exists
to avoid.

## 1. A workflow begins at its start events, not at the process

Version 1 reported a started workflow from a `start` execution listener of the `bpmn:process`. That
listener runs before the variables the workflow was started with exist. So the workflow aggregate's
id is not there yet, and every report to the cockpit is about that id.

The listener sits on every start event of the process instead, as an `end` listener. That is where
the VanillaBP Camunda 8 adapter puts its own listener, for the same reason: an `end` listener of a
start event still gates the transition, and by then the variables are written.

The `end` listener at the process stays where Version 1 had it. What this costs an application which
upgrades is decision 4.

## 2. Which cluster a call is about is said by the processing context

VanillaBP's deployment pipeline tells an extension that a workflow module is being wired, and later
that it is starting. Two facts this half needs are the adapter's to state: which configured adapter
a pipeline call belongs to, and which workflow module its run is for. `Camunda8ProcessingContext`
carries both, and every step reads them from there. A worker belongs to a cluster, and this is what
says which one. Nothing is guessed from a model.

The identifiers in a model are the ones the cluster it was prepared for will know. So one adapter's
scope is asked what a BPMN process is called there: the scope of the adapter whose run this is. The
first version of this half had no such answer to read. It built a candidate list of what every
configured Camunda 8 adapter would call the process, and it took the first spelling the model held.
That list turns ambiguous as soon as two adapter ids avoid name clashes differently, and the plain id
stood in it as a fallback, so a model of one cluster could be taken for another's.

The workers follow. They are opened per adapter id and workflow module, and what was wired is
remembered under the same pair. So a worker subscribes to exactly what its own cluster's models
carry. The first version opened them once per workflow module, across every cluster the adapters
said held it, and subscribed each of those clusters to the job types of all of them.

## 3. A called process is a step where it shares the caller's aggregate, and a case of its own where it does not

The cockpit shows business cases. A process started by a call activity which carries the workflow
aggregate of its caller is part of the case above it. Reporting its start and its end would show a
second case which nobody opened. So an execution-listener job reports nothing for such an instance,
and a user task of such a process is reported as a task of the case above it.

A called process with an aggregate of its own is a business case of its own. The core starts it as
a workflow of its own and builds its aggregate, and the cockpit shows it that way too. This follows
decision 61 of `business-cockpit`: notifications form one group per case, and only a process with
its own aggregate forms a group of its own.

**The case of an instance** is the highest instance above it which can be reached by calls that
share the aggregate. The walk starts at the instance and goes up the call hierarchy. It stops at the
first caller which does not share the aggregate. So in a chain Order → Shipping → Packing, where
Packing works on Shipping's aggregate and Shipping has its own, the case of Packing is Shipping.

**The core decides which processes share an aggregate.** The extension asks
`WorkflowTaskWiring#workflowsShareTheWorkflowAggregate` with the plain process ids, the same answer
the Camunda 8 adapter uses for its call activities. The extension does not build a check of its own.
It does not use `BpmsInitiatedStartInvoker#startsAWorkflowOfItsOwn`: that answer is `true` for a
process nobody declares and for an id left over after a rename, and neither says anything about the
caller. A process the core does not know shares nothing. So a call from a process nobody claims
starts a case of its own. VanillaBP does not design for processes it did not deploy, so this is
accepted.

What follows from it:

- The listener of the start event and of the end of a called process with an aggregate of its own
  reports the workflow, with the called instance as `workflowId`. Its report is sent at its start,
  like the one of a case nobody called.
- A user task in such a process names that instance as `workflowId` and no `subWorkflowId`. A user
  task of a step names the case as `workflowId` and the instance it sits in as `subWorkflowId`
  (decision 29).
- `workflowsOfAggregate` drops a called instance found by the search only where the instance shares
  the aggregate of its caller.
- A task read from the searchable storage (`referenceOf`) names the same case as its listener did.
- A cancelled step is a step which ended, not a case which closed. How a cancelled case is reported
  is decision 11: from 8.10 on it is reported as cancelled, and on 8.8 and 8.9 a case which was
  terminated rather than finished is not reported at all.

**Where the call hierarchy comes from.** The extension reads it from the cluster only where the
answer can differ from the instance itself. An instance nobody called is its own case. So is an
instance whose process shares its aggregate with no other process this extension wired for the
module, which is a called process with an aggregate of its own. Neither costs a request. Every other
called instance needs the chain: one call-hierarchy request, and one request per process definition
in it. Both answers never change, so they are remembered. The chain comes from the searchable
storage, which runs behind the engine. So the lookup waits for it as long as the adapter's
`workflow-visibility-timeout` says, like the 8.8 lookup of the root (decision 7). If that window runs
out, the root of the hierarchy is taken as the case, and the reason is logged.

The code is `Camunda8BusinessCases`, once for all lines. The 8.9 and 8.10 lines read the root of a
job from the job and need no request for that. They pay the requests above where a process shares
its aggregate with another one.

`Camunda8BusinessCasesTest` shows the walk, `Camunda8CockpitJobHandlerTest` the reports of both
kinds of called process. `Camunda8CockpitIT` shows it on a cluster: one call reaches a step on the
caller's aggregate, one a process with an aggregate of its own. It also checks the three calls of
`BusinessCockpitService` which read VanillaBP's note of the start first (`aggregateChanged` twice and
`getUserTask`), for both kinds of case.

## 4. The task listeners are the ones Version 1 wrote

The task listeners of a user task are byte-for-byte what Version 1 wrote. That holds for their type,
for their retries and for where they are inserted among the listeners the model already carries. It
is a promise rather than a preference. A cluster stores a process version per set of bytes. So if
anything about those listeners moved, an application which deploys the same models again after the
upgrade would leave every running workflow behind on the old version.

The start events of decision 1 are the one deliberate difference. Such a deployment does produce a
new version, because of them and for no other reason. Workflows which are already running stay on
the version they were started on and keep reporting their user tasks, because the task listeners of
that version are the ones this extension serves anyway. The documentation says this out loud rather
than hiding it. It is the price of reporting a workflow which knows what it is about.

## 5. A listener of this extension carries no retries - extended by decision 25

A report which cannot be written is a defect somebody has to see, and an incident is how a cluster
says so. Completing the job anyway would let the workflow run on while the cockpit loses the event.
So the listeners carry zero retries, and a failing job raises an incident at once.

The same rule decides where listeners are NOT added. A BPMN process which no workflow aggregate of
the application claims gets none. The jobs of such listeners would be handed to nobody, and they
would stop that workflow where it sits.

## 6. The adapter says whose call this is, so nothing is guessed from a model

*Merged into decision 2 on 2026-10-12, before the 1.0 release.* Decision 2 now says that the
processing context names the adapter id and the workflow module of a call, and that the workers are
opened per adapter id and workflow module.

## 7. A line differs in what it costs, not in what it reports - where the business id is read from superseded by decision 8

The extension reads two things which arrived with the 8.9 client: the root process instance of a job,
which is what tells a called process from a business case (decision 3), and the business id of a
process instance. Line 8.8 has neither, and 8.8 is a cluster version Camunda still supports, so
dropping the line would send those users away from the cockpit over a field.

Both are answered on 8.8 without changing what a report says.

The root comes from the cluster's call hierarchy, which 8.8 answers with the chain from the root down
to the instance asked about. `Camunda8CallHierarchy` lives once per line: on 8.9 and above it reads
the job, on 8.8 it asks. What a hierarchy answered is remembered per process instance, because an
instance's root is settled when the instance is created. A workflow which produces many jobs
therefore pays for one request.

The business id is the workflow aggregate's id. VanillaBP starts every workflow with that id, writes
it into a variable and finds the instance again by it, and the reference the cockpit asked about
carries it already. On 8.9 and above a business id the cluster holds wins, because an instance
somebody else started may carry a case name of its own. On 8.8 there is no such field, so the
aggregate id is the answer. Reading the variable back would cost a request to arrive at an id which
is already in hand.

That preference is gone. Decision 8 says why, and the rest of this entry stands.

What line 8.8 pays for this is written where it happens. The searchable storage serves the call
hierarchy, so it lags behind the transition whose listener is running. A job of a process which
just started can be asked about before the cluster knows it. How long the lookup waits for the
answer is the adapter's word: `vanillabp.adapters.<id>.workflow-visibility-timeout`, ten seconds by
default and zero for no waiting at all. Waiting means asking again: the lookup repeats the request
every 100 milliseconds until the storage answers or the window is used up (`ASK_AGAIN_AFTER` in
`Camunda8CallHierarchy`). The storage says nothing about when it will hold the answer, so there is
nothing to wait for other than the next attempt. The Camunda 8 adapter uses the same key when it
waits for that storage to show a workflow it knows is there. A cluster whose exporter is slow is
slow for both of them, so it is one number rather than two. It is not the time between two attempts
of a report. That time is short on purpose, and the outbox multiplies it by the attempts it allows.
When the window runs out, the lookup treats the job as a case of its own. That adds a case too many
to the cockpit, which is better than an incident on a workflow which is doing nothing wrong
(decision 5).

This is the shape every later gap of this kind takes. The per-line source directory carries how a
line finds something out, never what it then reports. Only a line which cannot answer at all would
be a reason to end the line. A field which arrived one minor version later is no such reason.

## 8. A report is built from the listener job, and the searchable storage answers what is true now - narrowed by decision 19, which takes the key VanillaBP wrote down at the start

The Business Cockpit builds a report at the moment of the event and lets it travel with its outbox
entry (decision 26 in the DECISIONS.md of vanillabp/business-cockpit). On Camunda 8 that moment is
inside the listener job, and this entry says where the values of that report come from.

They come from the job. An activated job carries the assignee, the candidate users and groups, the
due date and the follow-up date of the task it is about, and it names the version of the deployed
process. The two BPMN names are not on it. This extension reads them out of the model when it wires
the model, and keeps them with the listener. Nothing is asked of the cluster, and asking would not
help. An exporter writes the searchable storage, and that exporter runs behind the engine. So while
a job waits, the event it is about has not reached the storage yet. The values reach the bridge
through `Camunda8EventBeingReported`, and the cockpit asks the bridge by identifiers.

What the searchable storage still answers is a question about now. `BusinessCockpitService` reads
the tasks and the workflows of one case whenever the application asks, and the bridge searches the
storage for them. When the storage held no such record, that used to mean "ask again in a moment",
because a report was dispatched moments after its event. On this way there is no entry to hand
back, so the answer is now an empty result and a line in the log which names both readings of it. `PhaseTwoRetryLater` is
gone from this repository.

The business id of a workflow is the workflow aggregate's id, on every way a report is built and on
every release line. Stephan's rule of 2026-09-17: to VanillaBP a business key is a business key only
where it says what the `@Id` attribute of the workflow aggregate says. Camunda 7 fills the business
key with that attribute when VanillaBP starts the process, and Camunda 8.10 is to do the same. So
what a cluster holds beside that id is not a business key VanillaBP recognises, and the cockpit
names the aggregate's id instead. The reference already carries it, so nothing is read for it.

This replaces the preference which the second half of decision 7 gave to what the cluster holds.
Decision 7 was written before the rule. It also removes the one place where the two ways of building a report
could have said different things: a report from a listener job and a report read for
`aggregateChanged` now name the same id. `Camunda8BusinessIds` had nothing left to answer and is
gone from all three per-line source sets.

A business key can also come from somewhere else and NOT say what the aggregate's `@Id` attribute
says. What happens then is a question for the platform, not for this extension.

A details provider which fails now stops the workflow. It runs inside the listener job, and the
listeners of this extension carry no retries (decision 5). So the cluster raises an incident and
the transition waits. That is meant. Only reading happens on this way, but what is read has to be right,
and a defect which repetitions hide is a defect nobody fixes. The way to the cockpit server is the
other half and stays quiet: it runs over the outbox, and a cockpit server which is down for ten
minutes costs nobody an incident.

A user of this adapter sees two things change. A report carries what the case said when the event
happened, not what it said when the report went out. That is the whole point. And a report which cannot be built never goes out at all, where it used to go out
late.

## 9. An old line holds up a release, not a pull request - which lines a release waits for narrowed by decision 14, which lines a pull request builds replaced by decision 15

A pull request builds the current GA line and tests it against that line's cluster. Every other
line waits for the nightly matrix. Only a pull request which moves a pin runs the matrix itself,
because a pin of line 8.8 is not compiled by a build of line 8.9.

Stephan's rule of 2026-09-18. The waiting is right, because nothing between two releases is
released. Every artifact `main` produces is a SNAPSHOT, so a line which goes red in the night has
broken nothing anybody depends on. The morning is early enough to hear about it. That is what
the matrix is for: it finds a change of the main line which does not work on another one. Running
it on every pull request would buy hours of integration tests to learn the same thing earlier than
anybody needs it.

Two rules make that safe, and they are what this entry is for.

The first is the release. A release runs only while every current line is green in the full matrix,
tests and cluster included. The gate is that matrix itself, called from `release.yaml` before
anything is built for publication, and not a look at what the matrix said last night. Last night's
result is about last night's SNAPSHOTs of the VanillaBP Camunda 8 adapter and of the cockpit's
`extensions-commons`, and every build resolves those afresh. So the commit is the smaller half of
what "the same thing" means. There is no input which switches the gate off, because a defect published
to Maven Central cannot be taken back.

A line which is not GA yet is not asked. Line 8.10 is an alpha, it is published as a preview, and
it is still released. Its version says alpha, and nothing claims it was proven. When this was
written the matrix left that line out altogether, so "every line of the matrix" and "every GA
line" were the same sentence. Decision 14 put the line back into the matrix and told the two
apart: the matrix says which of its GA lines broke, and the release reads that rather than the
matrix as a whole. Either way the answer comes from the workflow and not from a list kept in
`release.yaml`.

The second is the issue. A line which breaks in the night gets a GitHub issue, so that the break is
seen and fixed rather than scrolled past. `release-lines-issue.yaml` opens it, one per line, and
writes the line, the commit, what the log said and a link to the run. A line which is still red the
next night gets a comment on the issue it already has. The workflow finds that issue again by the
label `release-lines` and by a title which names only the line. A line which is green again gets a comment saying
so, and the issue stays open. A green night is not a fix: the 8.8 defect of September 2026 lost a
workflow in about one run out of four, so three nights out of four it was green. Closing the issue
would also mean the next red night opens a second one, and one break would end up spread over
several. The person who merged the fix is the one who closes it.

## 10. The client pin of a line is the adapter's pin, raised by hand

The Camunda client this repository pins per line is the one
`camunda-community-hub/vanillabp-camunda8-adapter` pins for the same line. The value is copied into
`pom.xml` in a commit of its own. Nothing reads the other repository while building.

By hand and not by build, because the pin is what the client API check of a pull request works on.
That check names the enum literals and the interface methods a new client added. A changed
property is what starts it on a pull request. A pin resolved during the build would move without a
commit, so the one change nobody documents would also be the one nobody reads. Raising it by hand also keeps the two
repositories apart in time: the adapter can move on a day this one is mid-story.

What pays for the delay is the night. The cluster the integration tests start is the adapter's
word, taken from `camunda8-adapter-test-support`, so a pin left behind here means an older client
answering a newer cluster. Nothing fails over it. The literals the cluster gained arrive as
`UNKNOWN_ENUM_VALUE`, which reads like a fallback somebody wanted. So every line of the matrix ends
with a check: it holds the image it ran against next to the pin it was built with. The line is red
while the two name different versions. The issue of decision 9 is then what asks for the pin.

It had already happened on two lines at once when this was written. Line 8.9 pinned `8.9.18` while
its tests ran a cluster `8.9.19`, and line 8.10 pinned `8.10.0-alpha4` against `8.10.0-alpha5`. The
preview line was not built at night then, so nothing held its pin against its cluster. It is built
now, see decision 14, and the check runs there like on every other line.

## 11. A cancelled case is reported from 8.10 on, and the model pays a version for it

Camunda 8.10 brought the `cancel` execution listener. The cluster takes it on the PROCESS element
and nowhere else. It runs when an instance is terminated, after every child element has terminated
and before the instance reaches its final state. At that moment the `end` listener beside it does
not run. That is why a cancelled case used to stay open in the cockpit for good.

So this extension writes that listener. It carries the job type of the `end` listener at the same
process and the zero retries of decision 5. The worker which is open for that type receives the
job, and the case is reported as cancelled.

Whether the line has the construct is the adapter's answer, not a second rule here.
`Camunda8CancelListeners` of the VanillaBP Camunda 8 adapter answers it and writes the listener
(decision 27 in the DECISIONS.md of camunda-community-hub/vanillabp-camunda8-adapter). Decision 28
there says that an extension uses the adapter's own API for this. Asking it first matters. A
cluster of 8.8 or 8.9 refuses a model which carries the listener, and that fails the deployment of
the whole workflow module. A second copy of the rule is a rule which will be wrong in one of the two
places.

An upgrading application deploys a new process version on 8.10. A listener added to the process
changes the bytes of the process, and a cluster counts a version per set of bytes. Decision 4
accepts exactly that for the start events of decision 1. This is the second reason, and it works
the same way. Workflows which are already running stay on the version they were started on. They
keep reporting what the listeners of that version report, so a cancellation of theirs stays
unreported. Moving from a build of line 8.9 to a build of line 8.10 costs one more version as well.

Nothing is derived for the user tasks of a cancelled case. Every Camunda-managed user task this
extension reports carries its own `canceling` task listener. That listener runs on every release
line, and it runs before the listener of the process. So the tasks report themselves and the case
reports itself. Deriving a `CANCELED` per known open task would say a second time what already
arrived. The order of the two reports does not matter either: the cockpit server records an end
even when it holds something younger, and a second end of the same thing changes nothing.

Nor are the other open things of the instance looked up. A search of the cluster's secondary
storage from inside a listener job would wait for the exporter while the termination waits for the
job. And the answer would be about a moment the job has already left. What the cockpit shows is
user tasks, and those carry their own listener.

A failing cancel listener leaves an incident on the cancellation. That is measured rather than read
anywhere: `Camunda8CockpitIT` arms a details provider which throws, cancels the instance on
`camunda/camunda:8.10.0-alpha5`, and the cluster raises an incident on it. The instance then stays
in its cancellation until somebody resolves that incident. It is the same answer decision 5 gives
everywhere else. Here the alternative is worse than usual: completing the job anyway would let the
instance finish terminating with nobody told, and the case would stay open in the cockpit for good.

A terminate end event is not a cancellation on Camunda 8. The `end` listener runs, the instance
reads as completed, and no cancel job exists. Such a case is reported as completed, on every line,
and this entry does not change it. Deleting an instance creates no job at all and reports nothing.

What line 8.8 and line 8.9 still cannot say is written down in `GAPS.md`.

## 12. A line hands an application its own client and nothing else of ours

This repository is published once per Camunda 8 minor so that an application can stay on the
cluster it has. The POM is what keeps that promise, because the POM is what puts a Camunda client
on the application's classpath. Until 2026-09-20 it did not keep it.

The published POM was a copy of the source POM with `${revision}` filled in, which is all the
flatten mode `resolveCiFriendliesOnly` does. The client version stood in a property which the line
profile sets. An application activates no profile of ours, so it read the default of the file.
Every line therefore published a POM asking for the client of the current GA line. Read in the
installed artifacts of `0.9.0-8.8-SNAPSHOT`: the module POM named `io.camunda:camunda-client-java`
with no version and kept its parent, and the parent said `${camunda8.version.line-8.9}`. An
application on the 8.8 line would have been handed the 8.9 client, whose job activations an 8.8
cluster rejects. No line is released yet, so nothing of this reached a user.

Every version is written into the published POM now. The flatten plugin runs in its `oss` mode.
Each published module names its dependencies with resolved versions and carries no parent, no
`dependencyManagement`, no properties and no profiles. Nothing in it waits for something a reader
would have to activate or inherit. `Camunda8PublishedPomTest` reads that file in `core`, the one
module which names the client, and fails when the version in it is not the client this build was
compiled against or when a parent or a `dependencyManagement` is back. The flatten mode is set
once for every module, so the guard covers every module from there.

That file also says what this repository is, and two of those statements were wrong as soon as
every module carried its own copy. Maven appends the module path to the `url` and to all three
`scm` elements a child inherits, so each artifact named a page which does not exist, for example
`https://github.com/vanillabp/businesscockpit-camunda8-adapter/businesscockpit-camunda8-adapter`.
Four `child.*.inherit.append.path` attributes in the parent switch that off. Every artifact now
names the root of the repository, the same address for all of them. A link into a module
directory is not what we want anyway. It breaks as soon as a module moves, and a reader finds the
module from the root in one click. The other wrong statement was `distributionManagement`: every
published POM named our snapshot repository. Where we deploy is nothing a consumer can use, and
on an artifact which later sits on Maven Central it would point a reader at GitHub Packages. The
flatten plugin takes it out of the published POM. Since story 1470 the source names none either,
because the snapshots go to Maven Central through the profile `central-portal` of the parent. The
rule stays, so that a `distributionManagement` which comes back, here or from a parent, never
reaches the published POM. The same test guards both.

What one line needs is no business of another line's users. That rule is wider than the client,
and it is why the parent is dropped rather than only corrected. What this repository pins for its
own build is chosen for the newest line: the protobuf runtime, Testcontainers, Lombok, and the
Spring Boot and Quarkus versions it compiles against. None of it may arrive at an application
through us. A pin an application needs is named in the README and set by the application, and
every other pin stops at our own classpath.

The protobuf pin stays one number. It reaches no application now, so a number per line would
change nothing a user runs and would only lower what the tests of the older lines run against. The
comment at the pin says so, and what an application really resolves is a table in the README,
together with the one case where an application pins protobuf itself.

The VanillaBP Camunda 8 adapter found the same fault in its own artifacts on the same day and
answered it the same way, which is its decision 39. It now holds what the check knows. The class
`PublishedPom` sits in `camunda8-adapter-published-pom`, and that adapter publishes the module on
the same release lines this repository builds against. It stood in
`camunda8-adapter-test-support` until 2026-09-22. That module starts clusters, so a test which
wanted this one class got Testcontainers as well, and `core` wants no cluster.
`Camunda8PublishedPomTest` is the caller. It names what is ours: the artifact, the client of this
line, the address of this repository and the run a failure came from.
Two copies of one check drift apart, and a check like this one is only worth something while it
still runs in two years. The entry here stays ours, because the modules, the pins and what we ask
of them are ours.

The shared class cannot read one thing, the client to expect. The adapter reads it from its own
line descriptor and we cannot. Every line here compiles against the same adapter snapshot, so
that descriptor answers for whichever line the adapter was built for last. The build hands the
client to surefire instead, see the configuration in `core/pom.xml`.

See [What an application pins itself](./README.md#what-an-application-pins-itself).

## 13. The cockpit's workers lease what the adapter leases

Camunda 8.10 gave a job activation a lease. A worker which asks for one gets a token with every
job, and the cluster takes an answer to that job only from whoever holds the newest token.

This extension asks for it. Its workers hold a listener job from the activation until the answer,
because the report is built while the job waits, and that is the case the lease was made for. The
case is not a rare one. Take a details provider which needs longer than the lock. The cluster hands
an expired job out again about a second after the lock ran out. It does so for a listener job and
for an ordinary service task job, with a lease and without one, and it does not care which worker
name the job was held under. The second run writes its entry and completes the job. The first run
then answers for a job somebody else holds.

It is asked for through `Camunda8Workers.leaseTheActivations` of the VanillaBP Camunda 8 adapter,
the same entry point the worker options come from. That method decides, and this extension does
not: it knows whether the client of its release line has a lease at all, and it reads
`vanillabp.adapters.<id>.job-lease` of that adapter id. A copy of either rule here would be a
second opinion about the same job type.

There is no key of the cockpit's own, and there must not be one. A lease is a property of a job
type on a cluster, not of a component. Two subscribers of one type with different answers starve
each other: once a job went to a worker with a lease, a worker without one never sees that job
again, and no command takes the lease off it. The job types of this extension are built from the
identifiers the cluster knows, and they carry no adapter id. So a second application which deployed
the same models under the same prefix and the same tenant subscribes to exactly these types. One
value per cluster is the only answer which cannot starve anybody. See decision 36 in the
DECISIONS.md of camunda-community-hub/vanillabp-camunda8-adapter.

#### One worker is both runs

The race two runs have over one answer needs no second worker. A client which polls while its
handler works activates its own expired job again, so one worker is both runs, and the newer run of
the pair is the extension's own handler. Whether a worker polls while its handler holds a job
belongs to the client, not to the cluster. Camunda repaired it in 8.8.37, in 8.9.18 and in `8.10.0`
(`SUPPORT-34723`). Every line this repository pins carries the repaired client, so every line races
with itself. The old form is in the 8.8 releases up to 8.8.36, in the 8.9 releases up to 8.9.17,
and in the 8.10 pre-releases. Story `1362` tracked it down: `JobWorkerImpl` schedules the next poll
after a poll which brought jobs only when no job is left open. A handler which hangs therefore left
nothing scheduled, and the worker went quiet until its handler returned.

The client would build itself one job worker execution thread, and an application here does not
run on it. The VanillaBP Camunda 8 adapter hands the client an executor of its own, which schedules
the polls on threads nothing else uses. So a handler never sits on the thread a poll needs, and only
an adapter whose execution slots are all busy stops asking for more. See decision 18 in the
DECISIONS.md of camunda-community-hub/vanillabp-camunda8-adapter.

A second worker is the plainer way to get the same race. Every other worker on the job type is
offered the job in the same moment: a second pod of the application, a second adapter id in one
application where the name mode `use-prefix` or `none` leaves both ids on one job type, or an
extension with listener workers of its own, which is this extension.

#### What the lease settles, and what is left without one

On 8.10 the lease settles the race the same way for one worker as for two. The run which holds the
job wins. The run whose lock expired is refused with `409 INVALID_STATE`, "the supplied lease token
does not match". Measured on 2026-10-01 in six cluster runs against `camunda/camunda:8.10.0`, one of
them a pair which changed nothing but the client: with `8.10.0` the holding worker had the job back
after 5798 ms, with `8.10.0-alpha5` an outsider took it after 5243 ms.

A refused answer is not an incident. `Camunda8ListenerJobs.completeOrFail` of the adapter carries
the token and recognises the refusal, drops the answer with one line, and lets the handler return
as if the cluster had accepted it. So the job stays with the run holding it, no retry is counted
down, and decision 5 keeps its meaning: an incident is what a report which could not be BUILT
costs, and nothing else.

The cockpit is told the same thing twice, never two different things. Both runs build the report
of the same event and plan an outbox entry for it under the same idempotency key. Where the first
entry is still waiting, the second takes its place and one report goes out. Where the first was
already dispatched, both are sent, and the cockpit server keeps the newer one by the timestamp of
the event. That is the ordinary case here: a lock runs out in seconds and an outbox dispatches in
a fraction of one. Either way a person sees one created case. See decision 26 in the DECISIONS.md
of vanillabp/business-cockpit.

Line 8.8 and line 8.9 have no lease, in their clusters and in their clients. There the first
answer wins and the other run gets `Command 'COMPLETE' rejected with code 'NOT_FOUND'` as an HTTP
404. Story `1366` measured that in both orders on cluster and client `8.9.19`, with one worker
which had its own job back about a second after the lock ran out. A job which was finished long ago
answers exactly the same way, so nothing tells anybody that two runs did the same work. On those
lines the idempotency key of the outbox entry is the whole of the protection. The build of those
lines accepts the key and ignores it. That lets one configuration serve an application which moves
between lines.

#### How it is tested

`Camunda8CockpitIT` needs no second participant. It holds the report of a started case inside its
listener job, waits for the worker to be handed that same job again, and holds the second report too.
Then it lets the first one answer. The log says another activation holds the job, and nothing says
the job was failed. The case reaches the cockpit as created, and once the second run answers, the
instance carries no incident. The report of that case is built twice, once per run, which is what
the hold lets happen on purpose.

The job key comes out of the reports rather than out of an activation. The cockpit names a workflow
event by the key of the listener job the report was built in, so a details provider is handed that
name. Two held reports naming one key are one job which was handed out twice.

The test skips itself on 8.8 and on 8.9 through its assumption about the lease. Line 8.10 leaves no
test out, and no line of this repository does. The property `camunda8.line.excluded-test-tags` is
empty everywhere and stays, because the next line which needs an exclusion needs it in three places
at once.

## 14. The preview line is built every night, and a red one holds up nothing - narrowed by decision 16, which holds while no line is a preview line

The nightly matrix builds every line this repository defines, the preview line included. What the
cluster of that line cannot serve is left out one level deeper: the tests which need a
Camunda-managed user task carry the tag `user-task-listener-jobs`, and the `line-8.10` profile of
`pom.xml` excludes that tag in failsafe and in surefire.

Before this the whole line was left out of the matrix, and the argument for that was the same one:
a line which is always red stops being read. What it cost was everything else the line says.
Nineteen of the thirty integration tests need such a task, so the other eleven were thrown away with
them. Among those eleven are the deployment, the start of a case, the execution listeners, the search
in the secondary storage, and the two things which exist on 8.10 alone: the cancel listener of the
process of decision 11 and the job lease of decision 13. Those two are the reason the line exists,
and they had no automatic proof anywhere. Two defects of this line were found by hand in September
2026, a missing lease switch and an old protobuf pin, and both would have failed the first night
under the tag.

The exclusion sits in the profile and not in the workflow. It travels: a build by hand on 8.10
behaves like the night. And it goes away in one place, because the tag and the exclusion name each
other and both say what they cost. A variable in a workflow would be found by whoever already knew
it was there.

The tag has the same name and the same meaning in
`camunda-community-hub/vanillabp-camunda8-adapter`, whose 8.10 line runs green with it. Two
repositories doing the same thing two ways would make every reader learn it twice. See decision 43
in the DECISIONS.md of that repository.

A red preview line holds up no pull request and no release. That line is built against an alpha
which Camunda rewrites under us. A defect of the alpha may cost a night, but it may not cost a
release of the GA lines. So the matrix hands out `broken-ga-lines` beside its own result. The job
`lines-verified` needs every line and is what the night reads. The job `ga-lines` reports which of
the GA lines broke. The release gate reads the second, and so does the pull-request check of a
moved pin. The preview line is still released, as it always was. Its version says alpha, and
nothing claims it was proven. This narrows decision 9, which asked the release to wait for every
line of the matrix while the matrix held GA lines only.

The exclusion costs coverage, and the gate had to be measured rather than guessed. Nineteen
tests of thirty is a lot of code left uncovered, and the build stops below 85 per platform. The
first green run of this line says 86.43 % for Spring Boot and 87.40 % for Quarkus, against 90.80 %
and 91.77 % on line 8.9 in the same matrix run. So the gate holds on the preview line with about
one and a half points to spare, and no line of this repository gets a threshold of its own. If a
later test pushes it under the gate, the answer is a threshold in the `line-8.10` profile, with a
comment which ties the number to the exclusion so that both go away together. Lowering the gate
for every line would be the wrong answer to a defect of one alpha.

A red preview line still gets its GitHub issue in the night, the same as any other line, because
somebody has to decide whether it is the alpha's defect or ours. Answering that is what the tag
asks for at every pin move: deploy a user task with a `creating` listener, start an instance and
see whether the job arrives. Once one does, the tag and the exclusion go away together and the line
is proven whole.

## 15. A pull request waits for every line, the same as the night does

`build.yaml` calls `line-matrix.yaml` without a condition, so a pull request builds and tests
every line against that line's cluster. The check `line-pins-verified` reads the GA lines of the
matrix, and `pin-change` stays as a job which writes into the log whether a pin moved.

Decision 9 let a pull request build the current GA line alone and left the other lines to the
night. What paid for that was the runtime, and the way we work changed under it. The gate came
when every story was its own pull request, and the matrix once per story would have cost too much.
Since 2026-09-22 stories arrive as a wave in one pull request, so the matrix runs once per wave.

The lines build beside each other, so a pull request waits for the slowest of them and not for
their sum. Measured over three lines on the nights of 2026-09-22, 24 and 25: between 7m47s and
8m16s in total, with line 8.8 the slowest at 7m24s to 7m56s. A pull-request build on its own took
between 5m03s and 8m16s in the same days. So the matrix costs about the time the pull request had
anyway, plus two more runners. The comment in `line-matrix.yaml` names 42 minutes as its worst
run. That number is from before the tag which leaves the 8.10 tests out, and nobody should plan
with it.

What the old rule cost was harder to see. A pull request was green on one line and the night
found the same code red on another, and by then whoever wrote it had moved on. Waiting for the
night also means reading the night, and a red night belongs to whoever merged the day before.
That is a hand-over no rule can make.

One duty comes with the change. Whoever opens the wave watches it, and a line which goes red is
looked at while the other lines are still running.

The pin guard of a line now speaks on every pull request, and it stays red rather than warning.
Stephan decided that on 2026-10-01, when story 1392 raised the pin of line 8.8 and asked the
question. The cost is visible. The client pin of a line is raised by hand, while the adapter moves
its own pins by Renovate. So a pin which fell behind turns a pull request red which has nothing to
do with it, until somebody raises it. A warning would buy that pull request its green run back and
give up what decision 10 is for. An older client against a newer cluster breaks nothing and
proves less, and a warning is exactly how that stayed unseen on two lines at once.

Two things stayed. The release calls the matrix itself, before anything is built for publication,
which is the other half of decision 9 and is untouched. And the check counts the GA lines rather
than reading the matrix as a whole, so a line which never ran, or which was cancelled, is a line
which did not build. The old step left itself green whenever no pin had moved, which was almost
always, and a broken GA line would have been built, counted and still invisible.

A red preview line does not colour the run of a pull request either. The job of that line in
the matrix carries `continue-on-error`, so the result it hands out is a success and the job of
the caller stays green with it. The preview line is the only line which gets that. The night
still sees a break. Every line writes down what it did, and the job which sorts them reports all
broken lines in its `broken-all` output. That output is what the night reads instead of the job
result. `continue-on-error` changes the result a job hands out. It does not change `job.status`
inside the job, so the file that line wrote still says `failure`. For the same
reason `release-lines-issue.yaml` reads the steps of a line job as well as its conclusion,
because the API reports that forced success too. Stephan decided this on 2026-10-01, after
pull request 78 was red while its required check was green and could not be merged.

## 16. Every line is GA, so every line counts - extended by decision 17, which corrects what it says about a job handed out again

No line of this repository is a preview line. Camunda released `8.10.0` on 2026-09-29, so 8.8
and 8.9 are bugfix lines, 8.10 is the current GA line and a build without a profile is that
line. The next preview line is the one built against the first pre-release of 8.11.

Nothing in the line matrix was rebuilt for this. The preview line was never named in a workflow:
the job `discover` reads the property `camunda8.line.preview` out of the `line-*` profiles of
`pom.xml`, and `line-8.10` stopped carrying it. So `continue-on-error` is false for every line,
the job `ga-lines` counts 8.10 among the lines a pull request and a release wait for, and
`lines-verified` gives the same answer whichever of the two outputs it reads.

The input `preview-line-counts` and the output `broken-all` stay. They are what the night reads,
and while no line is a preview line they say the same thing as `broken`. Taking them out would
mean writing them again for 8.11, and the reason they exist is written down where they are.

One of the two tag exclusions of line 8.10 went with the pre-releases. The REST gateway of those
pre-releases dropped a whole activate-jobs batch as soon as it met a task listener job whose event
carries no user task action. So the creation of a Camunda-managed user task never finished
(`camunda/camunda#58193`). Nineteen of the thirty integration tests were left out over it,
eighteen by the tag `user-task-listener-jobs` and one by `jobs-handed-out-again`. Camunda closed
that defect for `8.10.0`.

The exclusion was not simply deleted on the strength of a closed issue. Decision 14 asked for a
measurement at every pin move, and this is what the measurement said. The line was run once with
no exclusion at all. Every test which needs a user task passed, and one test failed. So that tag
went, the line now runs twenty-nine of its thirty tests, and the other tag stayed. The test behind
that tag needs the cluster to offer a listener job whose lock ran out to a second activation, and
`camunda/camunda:8.10.0` does not. Three runs
on 2026-10-01 failed on it, two of them after waiting about 122 seconds and one after 41 seconds
in a socket timeout of the client. The cluster log held nothing at all about the job. That tells
this apart from the old defect, which wrote an uncaught NullPointerException into the log every
thirty seconds. The cause is open and the candidates are written down in the
profile. Nothing else was switched off for it.

That exclusion is a property now and no longer a setting inside the line profile. A profile
cannot carry it any more, because a build without a profile is the current GA line. The exclusion
would be missing from exactly the build which `.github/workflows/build.yaml` runs before it
publishes, and that is where the red test turned up first. So the tags a line leaves out are
`camunda8.line.excluded-test-tags`, surefire and failsafe both read it, and every line profile
sets it. Measured both ways on 2026-10-01: a build without a profile is green, and line 8.9 with
an empty value runs every test it has.

What this costs is the thing decision 14 bought. A defect of the 8.10 cluster now turns a pull
request red which has nothing to do with it. That is why the one test above is excluded rather
than left to turn every wave red. That is the price of a GA line, the same price the other two
lines pay. The alternative would be a line nobody has to believe. A pre-release is rewritten under
us and a GA release is not, and that is the whole difference between the two cases.

Stephan decided this on 2026-10-01, with the GA of 8.10 and the adapter's own move behind it.

## 17. One worker is both runs of a job whose lock ran out

*Merged into decision 13 on 2026-10-12, before the 1.0 release.* Decision 13 now says that one
worker is both runs of such a job, and that the lease settles the race for one worker as for two.

## 18. The test application sends the BPMS only what a model reads

Story 1389 made this repository build again by declaring aggregate values under
`declared-aggregate-values`: the `signers` list and the JPA `version` counter of the Spring Boot
test aggregate, and `signers` alone in the Quarkus one. A declaration says that somebody looked at
a value and knows what their BPMS makes of it. Here there was nothing to look at, because no model
of this repository reads a value of the aggregate at all.

Measured on 2026-10-03 against `origin/main`. The repository holds six BPMN models and no DMN
model. A search for `conditionExpression`, `zeebe:input`, `zeebe:output`, `zeebe:ioMapping` and
`zeebe:calledDecision` answers nothing, and so does a search for `loopCharacteristics`,
`inputCollection`, `inputElement` and `signers`. What the models do carry is `zeebe:userTask`,
`zeebe:formDefinition` and, in `calling-process.bpmn`, a `zeebe:calledElement` with
`propagateAllParentVariables="true"`. There is no condition and no mapping anywhere in them.
Zero of six models read a value.

So the values are held back instead of declared. `version` and `signers` of the Spring Boot
aggregate and `signers` of the Quarkus aggregate carry `@NoSyncWithBPMS`, and the three
`declared-aggregate-values` blocks are gone. The counter belongs to the persistence of the test
application, the signers belong to the case it keeps, and the BPMS has nothing to do with either.
A test application gets read as an example, so what it shares should be what an application would
share.

The question is asked per repository, because the models differ. In
`businesscockpit-camunda7-adapter` a multi-instance task reads `signers` through
`camunda:collection="${signers}"`, in the Spring Boot model and in the Quarkus one, so the
declaration is the right answer there and only `version` is held back. In
`businesscockpit-process-engine-api-adapter` no model reads a value either, and nothing had to
change: that repository declares nothing, and its `version` already carries the annotation.

Two things stay as they are. The customer of the test aggregate still travels, so that the test
application also shows a workflow which shares everything it has. And `allow-full-sync-with-bpms`
stays on `CockpitProcess`, although the permission has no effect there any more. `FullSyncCheck`
returns as soon as the aggregate holds something back, so the startup runs with the line and
without it. The other workflows of the test application need their own line, because the
permission is not inherited, and one switch fewer in a test proves nothing. The same line is
without effect in the PEA test application since 1389, for the same reason, and it stays there for
the same reason.

One question is left for Stephan. Following the rule all the way would keep the customer out of
the BPMS as well, and then the annotation belongs on the class. The test application would stop
showing the full sync case. Six workflows carry the permission for it, and that looks like intent
rather than an oversight.

Decided while story 1391 was built, on 2026-10-03.

The reasoning is written in its own words where a reader meets it, so no source, test or document
cites a decision number for it:

- `spring-boot/src/test/java/io/vanillabp/cockpit/camunda8/springboot/test/TestAggregate.java`,
  class javadoc and the javadoc of `version` and `signers`
- `quarkus/deployment/src/test/java/io/vanillabp/cockpit/camunda8/quarkus/it/TestAggregate.java`,
  class javadoc and the javadoc of `signers`

## 19. A change of a case reads nothing in the application's transaction, and VanillaBP's note of the start comes before the storage

`BusinessCockpitService.aggregateChanged(aggregate)` and `aggregateChanged(aggregate, userTaskIds)`
name a case by its aggregate id, and both run in the application's transaction. The bridge needs the
key of the workflow and its version for the report, and for a user task its assignee, its candidates
and its dates. Only the cluster's searchable storage knows all of that. An exporter which runs behind
the engine writes that storage. So a read there right after a start, or while the exporter stands
still, finds nothing. And a storage which is down fails the transaction which reads it.

So the bridge reads nothing in the application's transaction. Where it cannot answer without a
read, the Business Cockpit extension writes an outbox entry without a report, and the report is built
when the entry is dispatched (decision 43 of `business-cockpit`). That is where the storage is read.

This narrows decision 8, which says that the bridge searches the storage for the tasks and the
workflows of a case whenever the application asks. That no longer holds for a workflow whose key
VanillaBP wrote down, and nothing is searched in the application's transaction. The rest of decision
8 stands.

#### The note of the start

VanillaBP writes down the key and the version of a workflow when it starts it, together with the
adapter (decision 110 of `adapter-platform-integration`). `WorkflowElection#workflowStartOf` reads
that note. It asks no BPMS, waits for nothing and throws nothing. The bridge asks it first, once per
question.

The extension asks the bridge `workflowsOfAggregateRightAway` first. The bridge answers it out of the
note alone. Where the note names a key of this cluster AND a version, the bridge references the
workflow with them and asks the cluster nothing. So a change reported a moment after the start, while
the exporter is behind, reaches the cockpit with its version. Everything else answers nothing, and
the bridge reads nothing.

At the dispatch, `workflowsOfAggregate` reads the storage. Where the note has a key and no version,
it reads that one workflow by its key, for the version, and does not search. Where there is no note,
it searches the storage by the aggregate id variable, the way it always did. Where the storage holds
no record yet, the answer is empty, and the extension asks again a little later, for up to ten
minutes. After that the extension logs an error and leaves the entry blocked in the outbox. The
bridge throws no `PhaseTwoRetryLater` itself. An empty answer at the dispatch is what tells the
extension to try again, and the window is the extension's. A read by key which finds no record is said
with a debug line, not with a warning. The extension says it once at the first attempt and once more
where it gives up, so a warning per attempt would only repeat it. A search which finds nothing still
warns and names both readings of an empty answer, as decision 8 asks for.

`prefilledWorkflowDetails` reads nothing for a reference which names its version. The version is the
one of the reference, which is the one that picked the details provider. The business id is the
aggregate's id (decision 8). The process name is the one this extension read out of the model while
wiring it. That name and the one the storage holds are the same attribute of the same model, except
where a model was renamed while a workflow of an older version still runs. A report then shows the
name of the model wired now. That is accepted, because it lets the main way build a report without
any read. A reference without a version is read by its key, for the version, and the name comes from
the model there as well. Where the storage holds no record of it, the answer is empty.

A report is never sent without a version. The version picks the application's details provider. A
report without it is served by a details provider which names no version, so an application whose
providers all name one gets a report without details. The cockpit replaces what it shows with that
report, and the customer it showed is gone. A first version of this rule sent such a report, and on
cluster 8.8 it arrived with `"details":{}` while the case held its customer. So an empty version is
read by the key, from the storage, whatever `WorkflowStart#versionsAreReported` says. That flag tells
"not yet" (true) from "never" (false). On Camunda 8 the cluster counts a version for every workflow,
and the Camunda 8 adapter keeps a catalog, so its notes always say "not yet". The platform answers
"never" for a note which names no adapter, and for an adapter which said nothing about versions at
all. In both the storage still knows the version, and every details provider of the application may
name one.

A note which names another adapter is left out, and the storage is searched as if there were no note.
During a migration between two clusters that is a workflow of the other cluster, and its key would be
read on this one. A note which names no adapter is still taken, because the key itself still has to be
one of this BPMS, which `workflowKeyOf` checks. A key which is not a number is no key of this cluster,
and it is treated like no note.

No note covers every case where VanillaBP does not know. Nobody started the workflow, or it started
before VanillaBP wrote such notes, or the note is older than
`vanillabp.delivery.workflow-start-retention`, or the process does not belong to this application.
These cannot be told apart, so the bridge reads nothing into it, and it searches the storage at the
dispatch.

A key says what was true at the start. It does not say that the workflow still runs. The bridge uses
it to name the workflow in a report and never sends it to the cluster as a command. Since decision 5
of `adapter-platform-integration`, `adapterIdOfWorkflow` reads the note first and no longer throws
after a workflow ended, for as long as the note lives. So `aggregateChanged` reaches this bridge
after the end as well. The note names the workflow, the storage still holds the history of the ended
workflow, and the cockpit gets an update of the ended case. Nothing is sent to the cluster. That is
what an application asks for when it changes a case after its end.

#### User tasks

The bridge answers `false` to `reportsAChangedUserTaskRightAway`. So the extension asks the bridge
nothing in the application's transaction. It names the tasks from what VanillaBP wrote down when it
delivered them, and it writes an outbox entry without a report for each of them. When the entry is
dispatched, the extension calls `prefilledUserTaskDetails`, which reads the task by its key. A task
VanillaBP wrote nothing down about is searched first, with `userTaskOfAggregate` or
`userTasksOfAggregate`. That is a task delivered before VanillaBP wrote such notes, a task named by an
id of another BPMS, or a task of a called process without a note of the start. Where the storage holds
no record yet, the answer is empty, and the extension asks again in the same way as for a workflow.

The workflow of a task from the delivery log is the case: the note of the start names it, also for a
task of a called process. That is the workflow a listener job reports for the task, so the
sub-workflow `prefilledUserTaskDetails` reports for a called process stays the same.

A read of one task by its key which finds no record is said with a debug line, as for a workflow. The
searches `userTasksOfAggregate` and `userTaskOfAggregate` still warn where they find
nothing, with both readings, as decision 8 asks for. At the dispatch that may be a warning per
attempt. `BusinessCockpitService.getUserTask` reads a task by its key only after a search found it.

#### What was weighed and not taken

Camunda 8.10 can assign a business id to a running process instance, and sending the same id again is
documented as a no-op the broker answers itself. That looked like a way to check a workflow without
the storage. It replaces none of the three places where the cockpit waited for the storage, as they
stood on 2026-09-19:

1. `workflowsOfAggregate` and `userTasksOfAggregate` search by aggregate id. Their input is the
   aggregate id, not a key. The command needs the key of the instance, which is exactly what the
   search looks for. The note VanillaBP writes at the start answers this place for workflows.
2. `prefilledUserTaskDetails` and `prefilledWorkflowDetails` read a record while a report is built.
   They need values, such as the assignee or the version, and not a yes or a no. For a case which just
   ended the broker answers `404` while the storage still holds the record. Since decision 8 a report
   of an event is built from its listener job, so these reads only serve `aggregateChanged` and
   `getUserTask`.
3. On line 8.8 `Camunda8CallHierarchy` waits inside the listener job for the storage to name the root
   of a called process, for up to `workflow-visibility-timeout`. It needs the root key, which the
   no-op does not give. Lines 8.9 and 8.10 read the root from the job.

Two more facts against it. The platform assigns no business id when it starts an instance, so the
first call would be an assignment and not a no-op. And a `404` does not mean that an instance never
existed, because the engine forgets an instance which ended.

## 20. A workflow VanillaBP wrote down with its version is reported without the storage - narrowed by decision 21, which leaves every change the note cannot name to the dispatch

*Merged into decision 19 on 2026-10-12, before the 1.0 release.* Decision 19 now says that a note of
the start with a key and a version answers a change without the storage.

## 21. A change of a case reads nothing in the application's transaction, and the process name comes from the model - what it says about user tasks narrowed by decision 22

*Merged into decision 19 on 2026-10-12, before the 1.0 release.* Decision 19 now says that a change
of a case reads nothing in the application's transaction, and that the process name comes from the
model.

## 22. A changed user task reads nothing in the application's transaction

*Merged into decision 19 on 2026-10-12, before the 1.0 release.* Decision 19 now says that a changed
user task is reported when its entry is dispatched, and what a read by key says.

## 23. A details provider does not serve a user task, so the test applications mark their user tasks - extended by decision 24

`adapter-platform-integration` now ends the start when a task of a claimed process has no
`@WorkflowTask` method and is not marked with `implemented-externally=true`. This holds for a user
task too. Before, a user task without a method passed with a line at INFO.

A `@UserTaskDetailsProvider` method does not count as serving a user task. It says what the cockpit
shows about the task, and nothing about who completes it.

The test applications of both platforms have no `@WorkflowTask` method. The tests complete their
user tasks through the cluster. So all three configurations, `application.yaml` and
`by-adapter.yaml` of Spring Boot and `business-cockpit.yaml` of Quarkus, mark each user task of a
claimed process. The mark stands at the task, named by its element id, because that is the
narrowest place the key has. A process no workflow service of the application claims gets no mark,
because nothing is asked of its tasks. In the Quarkus application that is `IncidentDetailsProcess`.

The formatter removes comments from a YAML file, so the reason also stands in the Javadoc of
`Camunda8CockpitIT` and `Camunda8CockpitTest`.

## 24. The process nobody claims is marked at the workflow - extends decision 23

`adapter-platform-integration` now ends the start when a workflow module deploys a process which
no `@WorkflowService` claims, unless the process is marked with
`vanillabp.workflow-modules.<wm>.workflows.<id>.implemented-externally=true`.

The Quarkus application deploys `IncidentDetailsProcess` and claims it nowhere, because the test
`anUnclaimedProcessIsLeftAlone` needs such a process. So `business-cockpit.yaml` marks that process
at the workflow. Its tasks still get no mark, as decision 23 says. The mark at a task of a claimed
process says that somebody outside the application completes that task. The mark at the workflow
says that the application does not run the process at all.

The mark stands in the YAML of the Quarkus application. That file configures this one application
only, and nobody in it claims the process. The Spring Boot applications claim
`IncidentDetailsProcess`, so that process has no mark at the workflow there. Decision 25 gives them
a process of their own which nobody claims.

## 25. Only a process a workflow service claims gets listeners, and VanillaBP says which those are - extends decision 5

`adapter-platform-integration` sorts the processes of a workflow module into three kinds:

- claimed: a `@WorkflowService` names the process as its `bpmnProcess` or among its
  `secondaryBpmnProcesses`. A process a call activity starts counts, as long as the service of
  the caller names it.
- deployed but not claimed: the workflow module deploys the process, and no `@WorkflowService`
  names it. The start goes on only when the process is marked with
  `vanillabp.workflow-modules.<wm>.workflows.<id>.implemented-externally=true`.
- foreign: the process exists only in the cluster. VanillaBP never deployed it.

The rule is the same for all three cockpit adapters. Only a claimed process gets listeners of the
cockpit, and only a claimed process is reported. The other two kinds get no listener, no change to
their BPMN and no report.

Decision 5 already kept listeners away from a process nobody claims. What changes is how the
adapter finds out. It asks `WorkflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId,
bpmnProcessId)`. Before, it asked `resolveWorkflowAggregateIdName` and treated an exception as "not
claimed". The behaviour stays the same. VanillaBP still calls `wireBpmn` for a process nobody
claims, so the adapter has to ask.

A foreign process never reaches `wireBpmn`. The adapter never sees its model, and its jobs carry no
job type a worker of the cockpit subscribes to.

The test applications show all three kinds on both platforms. The Spring Boot applications deploy
`UnclaimedProcess`, which nobody claims, and both of their configurations mark it at the workflow.
The Quarkus application uses `IncidentDetailsProcess` for this, see decision 24. On both platforms
a test deploys `ForeignProcess` straight to the cluster.

## 26. A listener job carries what the details providers read

A `@UserTaskDetailsProvider` method may take a process variable with `@TaskParam`, and the SPI
promises it a value. A Camunda 8 job carries only the variables its worker names. So a worker of
this extension which asked for the variable with the workflow aggregate's id and for nothing else
left every `@TaskParam` at `null`. `@TaskParam` stays, and it gets its value on Camunda 8 as well.

#### What a worker asks for

For a job type of user tasks a worker names three kinds of variables:

- the variable with the workflow aggregate's id;
- the variables the details providers of those tasks read with `@TaskParam`;
- the multi-instance context of the task, in the variables the Camunda 8 adapter puts into the
  deployed model for its own workers (`Camunda8FetchVariables.collect`).

A worker of a workflow job type asks for the aggregate's id alone, because a
`@WorkflowDetailsProvider` takes no `@TaskParam`.

The job handler passes what the job carries as `UserTaskDetailsPrefill.variables`. It turns the
multi-instance variables into `UserTaskDetailsPrefill.multiInstances` with
`Camunda8MultiInstance.valuesOf`, which is the adapter's own reading. So `@MultiInstanceIndex`,
`@MultiInstanceTotal` and `@MultiInstanceElement` work on Camunda 8 too.

The names come from VanillaBP, per workflow module and BPMN process. It answers them with
`ExtensionHandlers#taskParameterNames`, and the extension passes the question on as
`BusinessCockpitEventPublisher.variablesTheDetailsProvidersRead`, with four values:
`workflowModuleId`, `bpmnProcessId`, `taskDefinition` and `bpmnTaskId`. `Camunda8CockpitWorkers`
asks once for each user-task listener of a job type. It passes the workflow module of the worker and
the BPMN process the listener sits in, as the application wrote it, and not as the cluster knows it.
A worker whose job type sits in several processes fetches the union of the answers. So a job carries
what a provider of its own process reads, and not what a provider of another process reads under the
same element id. The Camunda 8 adapter does the same for its `@WorkflowTask` workers with
`WorkflowTaskWiring.taskParameterNames`.

The answer can still name a variable too many. Where several providers of one key split the versions
of a process between them, VanillaBP also counts the provider for every task, although it may never
run. A job never misses a variable its own provider reads. `Camunda8CockpitWorkerVariablesTest`
shows it with two processes that use the same element id and have different providers. Each worker
asks only about its own process.

The worker asks when the workflow module starts, not while its models are wired. The providers are
scanned by then, and the adapter has linked every called process of the module to the
multi-instance elements of its caller.

#### A report built from the storage

A report after `aggregateChanged` and the answer of `getUserTask` are built from the cluster's
searchable storage, and a record of a user task there carries no variables. So
`Camunda8CockpitBridge.prefilledUserTaskDetails` asks the storage for the variables after it read the
task. Otherwise a `@TaskParam` parameter would receive `null` on that way and the value on the way
through the listener job, and a provider which leaves a detail out for `null` would take back what
the first report said. It asks for the same names a worker asks for:

- the names `BusinessCockpitEventPublisher.variablesTheDetailsProvidersRead` answers, with the
  workflow module, the BPMN process, the task definition and the element id of the reference. Those
  are the values the extension picks the details provider by;
- the multi-instance variables of the task (`Camunda8FetchVariables.collect`). The wiring keeps the
  adapter's `Camunda8MultiInstance.Registry` of a module in `Camunda8CockpitDeployments` when the
  module starts, because the bridge has no worker to take it from. The rounds are read with the same
  code as for a job.

The request is the search for the effective variables of the user task
(`newUserTaskEffectiveVariableSearchRequest`), filtered to these names and with full values. We
measured on 2026-10-09 against a cluster 8.10.0, with a user task inside a multi-instance subprocess:

- the plain search (`newUserTaskVariableSearchRequest`) answers the variables of every scope from the
  task up to the process instance. Where two scopes hold the same name, it answers both;
- the effective search answers the same scopes, but each name once, and the inner scope wins. That is
  what a job carries, so we take this one.

Both searches found the variables of the multi-instance round (`loopCounter`, the input element),
because the round is a scope above the task. The client has the effective search from 8.8.31 and
8.9.6 on. Every line here needs a newer cluster anyway (8.8.40, 8.9.21, 8.10.0).

It costs one more request per report, and only where a variable may be needed. Nothing is asked
where no provider of the task reads a variable, the task sits in no multi-instance element of its
own process, and no caller names its process by an expression. Where the storage answers 404 for
the variables, the bridge answers nothing, the same as for a task it does not hold yet. The
dispatch then asks again a little later.

**A process called by an expression.** Such a process learns its rounds from the variable its
caller hands down (`Camunda8MultiInstance.CHAIN_VARIABLE`). Its own model shows no round, so
`chainOf` is empty for its tasks, and a provider which reads no variable would have made the bridge
ask for nothing. The Camunda 8 adapter answers `Camunda8MultiInstance.Registry#mayBeHandedAChain`
for the process id as the cluster knows it. `true` means that a process of the same workflow
aggregate names this process by an expression, so a chain may be in that variable. Then the bridge
asks as well, and `Camunda8FetchVariables.collect` puts the chain variable among the names. `true`
does not mean that a chain is there. Where the caller runs no round, the variable is missing and
the task has no round, which is what a job says as well. The adapter's side of this is decision 75
of `camunda-community-hub/vanillabp-camunda8-adapter`.

**The storage may hold a task before its variables.** The exporter writes a task and its variables
as records of their own. Where the storage holds the task and not yet all of its variables, the
search answers fewer values, and the report carries fewer values. The search does not answer 404
then, so the bridge does not wait. This stays so. The storage gives no sign that the variables of a
task are complete, so there is nothing the bridge could wait for.

`Camunda8CockpitBridgeTest` and the two test applications show it. On both platforms a report after
`aggregateChanged` and an answer of `getUserTask` carry the value the first report carried. The
calling case of the test applications also calls a process by an expression once per round of a
multi-instance call activity. The provider of its task reads no variable, and the report from the
storage carries the same round as the first one.

## 27. VanillaBP names the variables per workflow module and BPMN process - extended by decision 28

*Merged into decision 26 on 2026-10-12, before the 1.0 release.* Decision 26 now says that VanillaBP
names the variables a details provider reads, per workflow module and BPMN process.

## 28. A report built from the storage reads the same variables as a listener job

*Merged into decision 26 on 2026-10-12, before the 1.0 release.* Decision 26 now says that a report
built from the searchable storage reads the same variables a listener job carries.

## 29. A task of a called process names its own process, and the search asks for every process of the case

A user task can sit in a process which the case started by a call activity. Two ways build the
reference of such a task, and both now say the same.

- The listener job of the task. It names the process the job comes from, which is the called
  process.
- A search of the cluster's searchable storage, for `getUserTask` and for `aggregateChanged`. Until
  now it asked for tasks of the case's process only. A task of a called process was never found:
  `getUserTask` answered nothing, and `aggregateChanged` left the task out or, with its id named,
  blocked the outbox entry after ten minutes. That hit the typical cockpit task hardest, a task
  without a `@WorkflowTask`, because only VanillaBP's delivery log knows the others.

**The reference names the process which holds the task.** `bpmnProcessId`, `processVersion`,
`taskDefinition` and `bpmnTaskId` all belong to that process. For a task of a called process that is
the called process, written the way the application wrote it. `workflowId` stays the case, the
instance at the top of the call hierarchy (decision 3), and `subWorkflowId` is the instance the
task sits in. This is the platform's rule as well, decision 123 of `adapter-platform-integration`.

Why the four values must come from one process:

- A class serves every process it declares. So wherever one workflow service declares both
  processes, a details provider is found by the case's process as well. The wrong id does not show
  while a provider is bound. It shows wherever VanillaBP keeps something per process: the version
  range of a provider per (class, process), the catalogue of version tags, the delivery record, the
  election's hint and the name of an element. The version of the called model paired with the id
  of the case's process matches a range nobody wrote.
- A provider declared only for the called process is not found by the case's process at all.
- `taskDefinition` is turned back from the cluster's spelling with the process it belongs to,
  because `use-prefix` scopes it by that process. `bpmnTaskId` exists only in the called model.

**The search names every process of the case.** These are the case's process and every process of
the workflow module which shares its workflow aggregate and which this extension wired on this
cluster, each spelled the way the cluster knows it. The set comes from the shared aggregate
(`WorkflowTaskWiring#workflowsShareTheWorkflowAggregate`) and not from the call activities in the
models. A call activity may name its process by an expression, and then no graph of calls holds the
process it reaches. The Camunda 8 adapter draws the same line for such a call. A process with a
workflow aggregate of its own is left out: it is a case of its own, although the cluster copies the
caller's variables into it, the aggregate's id included.

The processes go into the search itself. The search does not run without them and sort the answer
out afterwards. Without a process condition the search reads every task of the tenant whose process
carries a variable of that name and value. Under `name-clash-avoidance: none` other workflow
modules and other applications share that tenant, and such tasks would push the wanted ones off the
page. 8.10 asks once, with `bpmnProcessId` set to "one of these ids". The 8.8 and 8.9 clients only
know "this one id", so those lines send one request per process and put the answers together. The
class `Camunda8UserTaskSearch` exists once per line for that.

The aggregate's id has to reach the called process for any of this to work. Camunda 8 copies the
caller's variables into a called instance unless the call activity says
`propagateAllParentVariables="false"`, and the Camunda 8 adapter writes `true` where the model says
nothing. A call activity saying `false` without an input mapping of the aggregate's id variable
leaves its tasks unfound, and their listener jobs fail. The wiki says so.

`Camunda8CockpitBridgeTest` shows the search and the reference. `Camunda8CockpitIT` and its Quarkus
twin show it on a cluster: a case calls one process by its id, one by an expression and one with an
aggregate of its own. The first report and the one after `aggregateChanged` pick the same provider,
and the task of the case of its own is neither read nor reported.

## 30. A called process with a workflow aggregate of its own is a case of its own

*Merged into decision 3 on 2026-10-12, before the 1.0 release.* Decision 3 now says which called
process is a step and which is a case of its own, and how the case of an instance is found.

## 31. The wiring names the user tasks of a model, so their providers are checked against its version

Built by story 1476 on 2026-10-11, with decision 64 of the Business Cockpit.

The Business Cockpit ends the start where a user task of the deployed model has details providers
but none of them covers the version the cluster gave the model. VanillaBP does that check. It knows
the providers, but not the user tasks of a model. So `Camunda8CockpitWiring#wireBpmn` names every
user task it adds a listener to (`BusinessCockpitEventPublisher#userTaskWired`). It passes the same
values the worker reports later: the plain task definition and the element id.

A user task of a called process is reported with the called process and with the version its job
names, not with the caller's. The version of a task provider refers to the model the task belongs
to. `Camunda8CockpitJobHandlerTest` holds that.
