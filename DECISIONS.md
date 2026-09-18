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

## 3. A called process is a step, not a case

The cockpit shows business cases. A process started by a call activity is part of the case above it.
It carries the same workflow aggregate, so reporting its start and its end would show a second case
which nobody opened. An execution-listener job therefore reports nothing when its process instance
has a root instance above it, and a user task of such a process is reported as a task of the
workflow that root instance is.

A workflow which was terminated rather than finished is not reported at all before Camunda 8.10. The
cluster has no listener for it, and the `end` listener of a process does not run when the instance is
cancelled. The cockpit keeps showing such a case as it last heard about it, until the `canceled`
execution listener of 8.10 can be wired.

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

## 9. An old line holds up a release, not a pull request

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

A line which is not current yet is not asked. Line 8.10 is an alpha, it is published as a preview,
and the nightly matrix already leaves it out because a user-task listener job never reaches its
worker on that alpha. The list in `line-matrix.yaml` is the one place which says which lines are
proven, and the release reads it by running that workflow rather than keeping a list of its own.
The preview line is still released. Its version says alpha, and nothing claims it was proven.

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
its tests ran a cluster `8.9.19`, and line 8.10 pinned `8.10.0-alpha4` against `8.10.0-alpha5`. A
line the night does not build is not covered, which today is the preview line.
