# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry of
THIS repository only. A decision which the platform shares has its own entry in
`adapter-platform-integration`, written from that side, and one the Business Cockpit shares has its
entry in `business-cockpit`; a pointer into another repository is the fragile kind this log exists
to avoid.

## 1. A workflow begins at its start events, not at the process

Version 1 reported a started workflow from a `start` execution listener of the `bpmn:process`.
That listener runs before the variables the workflow was started with exist, so the workflow
aggregate's id - the one thing every report to the cockpit is about - is not there yet. The
listener therefore sits on every start event of the process instead, as an `end` listener, which
is where the VanillaBP Camunda 8 adapter puts its own listener for the same reason: an `end`
listener of a start event still gates the transition, and by then the variables are written.

The `end` listener at the process stays where Version 1 had it. What this costs an upgrading
application is decision 4.

## 2. Which cluster a workflow module runs on is asked of the adapter

VanillaBP's deployment pipeline tells an extension that a workflow module is being wired and later
that it is starting, but not which of the configured adapters it is doing that for: the callbacks
carry the workflow module and the processing context, and `Camunda8ProcessingContext` names no
adapter id either. The extension needs one to open a worker, because a worker belongs to a cluster.

So the question is turned around. The adapters themselves know which workflow modules they opened,
and every one of them registers that with its client while it starts - which the pipeline does for
every adapter of a module before it starts any extension of it. The extension asks the clients
rather than the callback, opens its workers once per workflow module, and covers every cluster the
module actually reached.

The same turn answers the other identifier question. A model reaching this extension already
carries the identifiers its cluster will know, while the callback hands over the plain BPMN
process id, and which of the two spellings a model uses depends on the adapter it was prepared
for. Instead of guessing, every configured Camunda 8 adapter is asked what it would call the
process and the model answers which of those it holds.

## 3. A called process is a step, not a case

The cockpit shows business cases. A process started by a call activity is part of the case above
it: it carries the same workflow aggregate, and reporting its start and its end would show a
second case which nobody opened. So an execution-listener job of a process instance which has a
root instance above it reports nothing, and a user task of such a process is reported as a task of
the workflow that root instance is.

A workflow which was terminated rather than finished is not reported at all before Camunda 8.10:
the cluster has no listener for it, and the `end` listener of a process does not run when the
instance is cancelled. What the cockpit shows of such a case stays at the last event it did hear
about, until the `canceled` execution listener of 8.10 can be wired.

## 4. The task listeners are the ones Version 1 wrote

The task listeners of a user task are byte-for-byte what Version 1 wrote: their type, their
retries and where they are inserted among the listeners the model already carries. That is a
promise rather than a preference. A cluster stores a process version per set of bytes, so an
application which deploys the same models again after the upgrade would leave every running
workflow behind on the old version if anything about those listeners moved.

The start events of decision 1 are the one deliberate difference, so such a deployment does
produce a new version - because of them and for no other reason. Running workflows stay on the
version they were started on and keep reporting their user tasks, because the task listeners of
that version are the ones this extension serves anyway. That is worth saying out loud in the
documentation rather than hiding, and it is the price of reporting a workflow which knows what it
is about.

## 5. A listener of this extension carries no retries

A report which cannot be written is a defect somebody has to see, and an incident is how a cluster
says so; completing the job anyway would let the workflow run on while the cockpit loses the
event. So the listeners are written with zero retries and a failing job raises an incident at
once.

The same rule decides where listeners are NOT added: a BPMN process no workflow aggregate of the
application claims gets none, because the jobs of such listeners would be handed to nobody and
would stop that workflow where it sits.
