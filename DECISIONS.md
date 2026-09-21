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

## 2. Which cluster a call is about is said by the processing context - the detour of the first version superseded by decision 6

VanillaBP's deployment pipeline tells an extension that a workflow module is being wired, and later
that it is starting. `Camunda8ProcessingContext` names the adapter id and the workflow module of
that run. A worker belongs to a cluster, and this is what says which one.

The first version of this half had no such answer to read. It asked the adapters which workflow
modules they had opened and opened its workers per module across all of them. To learn which
spelling of a BPMN process a model carried, it tried what every configured adapter would call it.
Decision 6 says what that cost and what replaced it.

## 3. A called process is a step, not a case - what a cancelled workflow reports superseded by decision 11

The cockpit shows business cases. A process started by a call activity is part of the case above it.
It carries the same workflow aggregate, so reporting its start and its end would show a second case
which nobody opened. An execution-listener job therefore reports nothing when its process instance
has a root instance above it, and a user task of such a process is reported as a task of the
workflow that root instance is.

A workflow which was terminated rather than finished was not reported at all when this was written.
Decision 11 replaces that half: 8.10 brought the listener, a build of that line writes it, and a
cancelled case is now reported as cancelled. On 8.8 and 8.9 the paragraph still holds, and the
sentence about a called process holds on every line - a cancelled called process is a step which
ended, not a case which closed.

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

## 5. A listener of this extension carries no retries

A report which cannot be written is a defect somebody has to see, and an incident is how a cluster
says so. Completing the job anyway would let the workflow run on while the cockpit loses the event.
So the listeners carry zero retries, and a failing job raises an incident at once.

The same rule decides where listeners are NOT added. A BPMN process which no workflow aggregate of
the application claims gets none. The jobs of such listeners would be handed to nobody, and they
would stop that workflow where it sits.

## 6. The adapter says whose call this is, so nothing is guessed from a model

Two of the facts this half needs are the adapter's to state: which configured adapter a pipeline call
belongs to, and which workflow module its run is for. `Camunda8ProcessingContext` carries both now,
and every step reads them from there.

What that ends is a guess with no upper bound on how wrong it could be. The identifiers in a model
are the ones the cluster it was prepared for will know. So this half used to build a candidate list
of what every configured Camunda 8 adapter would call the process, and it took the first spelling the
model held. That list turns ambiguous as soon as two adapter ids avoid name clashes differently, and
the plain id stood in it as a fallback, so a model of one cluster could be taken for another's. Now
one adapter's scope is asked, the one whose run this is.

The workers follow. They used to be opened once per workflow module, across every cluster the
adapters said held it, and each of those clusters was subscribed to the job types of all of them.
They are opened per adapter id and workflow module instead, and what was wired is remembered under
the same pair. So a worker subscribes to exactly what its own cluster's models carry.

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

What line 8.8 pays for this is written where it happens. The call hierarchy is served by the
searchable storage, so it lags behind the transition whose listener is running, and a job of a
process which just started can be asked about before the cluster knows it. How long the lookup waits
for the answer is the adapter's word: `vanillabp.adapters.<id>.workflow-visibility-timeout`, ten
seconds by default and zero for no waiting at all. Waiting means asking again: the lookup repeats the
request every 100 milliseconds until the storage answers or the window is used up
(`ASK_AGAIN_AFTER` in `Camunda8CallHierarchy`). The storage says nothing about when it will hold the
answer, so there is nothing to wait for other than the next attempt. It is the key the Camunda 8 adapter waits out for
the same storage when it knows a workflow is there. A cluster whose exporter is slow is slow for both
of them, so it is one number rather than two. It is not the distance between two attempts of a
report, which is short on purpose and is multiplied by the attempts the outbox allows. When the
window runs out, the lookup treats the job as a case of its own. That adds a case too many to the
cockpit, which is better than an incident on a workflow which is doing nothing wrong (decision 5).

This is the shape every later gap of this kind takes. The per-line source directory carries how a
line finds something out, never what it then reports. A line which cannot answer at all is what would
end a line, and a field which arrived one minor later is not that.

## 8. A report is built from the listener job, and the searchable storage answers what is true now

The Business Cockpit builds a report at the moment of the event and lets it travel with its outbox
entry (decision 26 in the DECISIONS.md of vanillabp/business-cockpit). On Camunda 8 that moment is
inside the listener job, and this entry says where the values of that report come from.

They come from the job. An activated job carries the assignee, the candidate users and groups, the
due date and the follow-up date of the task it is about, and it names the version of the deployed
process. The two BPMN names are not on it, so they are read out of the model while this extension
wires it and kept with the listener. Nothing is asked of the cluster, and asking would not help: the
searchable storage is written by an exporter which runs behind the engine, so the event a job is
about has not reached it while that job waits. `Camunda8EventBeingReported` is how the values reach
the bridge, which the cockpit asks by identifiers.

What the searchable storage still answers is a question about now. `BusinessCockpitService` reads
the tasks and the workflows of one case whenever the application asks, and the bridge searches the
storage for them. A record it holds none of used to mean "ask again in a moment", because a report
was dispatched moments after its event. There is no entry to hand back on this way, so the answer is
now an empty result and a line in the log which names both readings of it. `PhaseTwoRetryLater` is
gone from this repository.

The business id of a workflow is the workflow aggregate's id, on every way a report is built and on
every release line. Stephan's rule of 2026-09-17: to VanillaBP a business key is a business key only
where it says what the `@Id` attribute of the workflow aggregate says. Camunda 7 fills the business
key with that attribute when VanillaBP starts the process, and Camunda 8.10 is to do the same. So
what a cluster holds beside that id is not a business key VanillaBP recognises, and the cockpit
names the aggregate's id instead. The reference already carries it, so nothing is read for it.

This replaces the preference the second half of decision 7 gave to what the cluster holds, which was
written before the rule was. It also removes the one place where the two ways of building a report
could have said different things: a report from a listener job and a report read for
`aggregateChanged` now name the same id. `Camunda8BusinessIds` had nothing left to answer and is
gone from all three per-line source sets.

What happens where a business key taken over from somewhere else does NOT say what the aggregate's
`@Id` attribute says belongs to the platform, not to this extension.

A details provider which fails now stops the workflow. It runs inside the listener job, the
listeners of this extension carry no retries (decision 5), so the cluster raises an incident and the
transition waits. That is meant. Only reading happens on this way, but what is read has to be right,
and a defect which repetitions hide is a defect nobody fixes. The way to the cockpit server is the
other half and stays quiet: it runs over the outbox, and a cockpit server which is down for ten
minutes costs nobody an incident.

What a user of this adapter sees differently afterwards is two things. A report carries what the
case said when the event happened rather than what it said when the report went out, which is the
whole point. And a report which cannot be built never goes out at all, where it used to go out
late.

## 9. An old line holds up a release, not a pull request - which lines a release waits for narrowed by decision 14

A pull request builds the current GA line and tests it against that line's cluster. Every other
line waits for the nightly matrix. Only a pull request which moves a pin runs the matrix itself,
because a pin of line 8.8 is not compiled by a build of line 8.9.

Stephan's rule of 2026-09-18. The waiting is right, because nothing between two releases is
released. Every artifact `main` produces is a SNAPSHOT, so a line which goes red in the night has
broken nothing anybody depends on, and the morning is early enough to hear about it. That is what
the matrix is for: it finds a change of the main line which does not work on another one. Running
it on every pull request would buy hours of integration tests to learn the same thing earlier than
anybody needs it.

Two rules make that safe, and they are what this entry is for.

The first is the release. A release runs only while every current line is green in the full matrix,
tests and cluster included. The gate is that matrix itself, called from `release.yaml` before
anything is built for publication, and not a look at what the matrix said last night. Last night's
result is about last night's SNAPSHOTs of the VanillaBP Camunda 8 adapter and of the cockpit's
`extensions-commons`, which every build resolves afresh, so the commit is the smaller half of what
"the same thing" means. There is no input which switches the gate off, because a defect published
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
next night gets a comment on the issue it already has, which is found again by the label
`release-lines` and a title naming only the line. A line which is green again gets a comment saying
so, and the issue stays open. A green night is not a fix: the 8.8 defect of September 2026 lost a
workflow in about one run out of four, so three nights out of four it was green. Closing the issue
would also mean the next red night opens a second one, and one break would end up spread over
several. The person who merged the fix is the one who closes it.

## 10. The client pin of a line is the adapter's pin, raised by hand

The Camunda client this repository pins per line is the one `vanillabp/camunda8-adapter` pins for
the same line. The value is copied into `pom.xml` in a commit of its own. Nothing reads the other
repository while building.

By hand and not by build, because the pin is what the client API check of a pull request works on.
That check names the enum literals and the interface methods a new client added, and what calls it
onto a pull request is the changed property. A pin resolved during the build would move without a commit, so the one
change nobody documents would also be the one nobody reads. Raising it by hand also keeps the two
repositories apart in time: the adapter can move on a day this one is mid-story.

What pays for the delay is the night. The cluster the integration tests start is the adapter's
word, taken from `camunda8-adapter-test-support`, so a pin left behind here means an older client
answering a newer cluster. Nothing fails over it. The literals the cluster gained arrive as
`UNKNOWN_ENUM_VALUE`, which reads like a fallback somebody wanted. So every line of the matrix ends
by holding the image it ran against the pin it was built with, and the line is red while the two
name different versions. The issue of decision 9 is then what asks for the pin.

It had already happened on two lines at once when this was written. Line 8.9 pinned `8.9.18` while
its tests ran a cluster `8.9.19`, and line 8.10 pinned `8.10.0-alpha4` against `8.10.0-alpha5`. The
preview line was not built at night then, so nothing held its pin against its cluster. It is built
now, see decision 14, and the check runs there like on every other line.

## 11. A cancelled case is reported from 8.10 on, and the model pays a version for it

Camunda 8.10 brought the `cancel` execution listener. The cluster takes it on the PROCESS element
and nowhere else. It runs when an instance is terminated, after every child element has terminated
and before the instance reaches its final state. That is the one moment the `end` listener beside
it does not run, which is why a cancelled case used to stay open in the cockpit for good.

So this extension writes that listener. It carries the job type of the `end` listener at the same
process and the zero retries of decision 5. The worker which is open for that type receives the
job, and the case is reported as cancelled.

Whether the line has the construct is the adapter's answer, not a second rule here.
`Camunda8CancelListeners` of the VanillaBP Camunda 8 adapter answers it and writes the listener
(decision 28 in the DECISIONS.md of vanillabp/camunda8-adapter). Asking it first matters. A cluster
of 8.8 or 8.9 refuses a model which carries the listener, and that fails the deployment of the
whole workflow module. A second copy of the rule is a rule which will be wrong in one of the two
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
flatten mode `resolveCiFriendliesOnly` does. The client version stood in a property that the line
profile sets, and an application activates no profile of ours, so it read the default of the file.
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
names the root of the repository, the same address for all of them, and a link into a module
directory is not what we want anyway: it breaks as soon as a module moves, and a reader finds the
module from the root in one click. The other wrong statement was `distributionManagement`: every
published POM named our snapshot repository. Where we deploy is nothing a consumer can use, and
on an artifact which later sits on Maven Central it would point a reader at GitHub Packages. The
flatten plugin takes it out of the published POM and the source keeps it, because the deploy reads
it from the model of the running build. The same test guards both.

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
`PublishedPom` sits in `camunda8-adapter-test-support`, and that adapter publishes the module on
the same release lines this repository builds against. `Camunda8PublishedPomTest` is the caller.
It names what is ours: the artifact, the client of this line and the address of this repository.
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
case is not a rare one. A details provider which needs longer than the lock loses the job, the
cluster hands it out again, the second run writes its entry and completes the job, and the first
run then completes a job somebody else holds. Without a lease the cluster took that late answer
and said nothing about it.

It is asked for through `Camunda8Workers.leaseTheActivations` of the VanillaBP Camunda 8 adapter,
the same entry point the worker options come from. That method decides, and this extension does
not: it knows whether the client of its release line has a lease at all, and it reads
`vanillabp.adapters.<id>.job-lease` of that adapter id. A copy of either rule here would be a
second opinion about the same job type.

There is no key of the cockpit's own, and there must not be one. A lease is a property of a job
type on a cluster, not of a component. Two subscribers of one type with different answers starve
each other: once a job went to a worker with a lease, a worker without one never sees that job
again, and no command takes the lease off it. The job types of this extension are built from the
identifiers the cluster knows and carry no adapter id, so a second application which deployed the
same models under the same prefix and the same tenant subscribes to exactly these types. One
value per cluster is the only answer which cannot starve anybody. See decision 36 in the
DECISIONS.md of vanillabp/camunda8-adapter.

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

Line 8.8 and line 8.9 have no lease, in their clusters and in their clients. There the older
answer wins, as it always did. The build of those lines accepts the key and ignores it, which is
what lets one configuration serve an application that moves between lines.

Measured rather than read. `Camunda8CockpitIT` holds the details provider of a started case
inside its listener job until the lock runs out, and then activates that job once more with a
lease, the way a second pod would. The held run is released afterwards, so its answer arrives
last. On `camunda/camunda:8.10.0-alpha5` the cluster refuses it, the log says another activation
holds the job, the job is not failed, the case reaches the cockpit as created, and the instance
carries no incident once the newer activation completes.

The second activation is the test's own and not a redelivery, the same way the adapter's lease
test does it. What is under test is the ORDER of the two answers. A redelivery cannot carry that
order: the run which holds the report sits in the handler of the worker the job came from, and
that worker is the one place the job does not turn up again.

An earlier version of this entry said the cluster does not redeliver at all. That was wrong.
Story `1346` measured it on `camunda/camunda:8.10.0-alpha5`. The cluster hands an expired job out
again about a second after the lock ran out. It does so for a listener job and for an ordinary
service task job, with a lease and without one, and it does not care which worker name the job
was held under. An open job worker gets it as readily as an activate command sent by hand.

The one worker which does not get it back is the worker whose handler is still holding it. It is
offered nothing while that handler runs, with no line in its log, and it gets the job the moment
the handler returns. This does not change what an application depends on: every other worker has
the job about a second after the lock ran out, and a restarted application is exactly that, a new
client with new workers. So what a shutdown left to its lock does come back.

The race two runs have over one answer therefore has a condition. It starts only where a second
worker can activate the job, and that worker has to be on the same job type. A second pod of the
application is the usual way to have one. Two adapter ids in one application are another, where
the name mode `use-prefix` or `none` leaves both ids on one job type. The third is an extension
which opens listener workers of its own on the same cluster, which is this extension. An
application which runs as a single pod with one worker starts no second run beside a slow
provider, and its job waits for the handler holding it.

That is no promise about a single pod. Story `1362` chased the reason down, and it is the client,
not the cluster and not the transport. `JobWorkerImpl` schedules the next poll after a poll which
brought jobs only when no job is left open. Otherwise the polling comes back through
`handleJobFinished()`, which runs after the handler returns. A handler which hangs therefore leaves
nothing scheduled, and the worker goes quiet at its first empty poll after that. Measured at the
gateway: not one activation request over two minutes, over REST and over gRPC, while a command sent
by hand got the job at once, and the same worker had the job 20 seconds after its handler returned.

Camunda repaired this in 8.8.37 and in 8.9.18. The old form is in the 8.8 releases up to 8.8.36,
in the 8.9 releases up to 8.9.17, and in both 8.10 alphas. This repository pins 8.8.37 and 8.9.19, so only the preview line
still carries it, and a repaired client polls again while a handler holds a job. One thing survives
the repair. The client runs one job worker execution thread by default, and there the handler and
the scheduled poll share that thread, so a blocking handler silences a repaired client as well.

The test skips itself where the build does not lease. It needs no user task, so it runs on the
preview line in the nightly matrix, which is where this is proven every night since decision 14.

## 14. The preview line is built every night, and a red one holds up nothing

The nightly matrix builds every line this repository defines, the preview line included. What the
cluster of that line cannot serve is left out one level deeper: the tests which need a
Camunda-managed user task carry the tag `user-task-listener-jobs`, and the `line-8.10` profile of
`pom.xml` excludes that tag in failsafe and in surefire.

Before this the whole line was left out of the matrix, and the argument for that was the same one:
a line which is always red stops being read. What it cost was everything else the line says.
Nineteen of the thirty integration tests need such a task, so eleven were thrown away with them,
and among those eleven are the deployment, the start of a case, the execution listeners, the search
in the secondary storage, and the two things which exist on 8.10 alone: the cancel listener of the
process of decision 11 and the job lease of decision 13. Those two are the reason the line exists,
and they had no automatic proof anywhere. Two defects of this line were found by hand in September
2026, a missing lease switch and an old protobuf pin, and both would have failed the first night
under the tag.

The exclusion sits in the profile and not in the workflow. It travels: a build by hand on 8.10
behaves like the night. And it goes away in one place, because the tag and the exclusion name each
other and both say what they cost. A variable in a workflow would be found by whoever already knew
it was there.

The tag has the same name and the same meaning in `vanillabp/camunda8-adapter`, whose 8.10 line
runs green with it. Two repositories doing the same thing two ways would make every reader learn it
twice. See decision 31 in the DECISIONS.md of that repository.

A red preview line holds up no pull request and no release. That line is built against an alpha
which Camunda rewrites under us, and a defect of the alpha may cost a night, it may not cost a
release of the GA lines. So the matrix hands out `broken-ga-lines` beside its own result: the job
`lines-verified` needs every line and is what the night reads, and the job `ga-lines` reports which
of the GA lines broke. The release gate reads the second, and so does the pull-request check of a
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
