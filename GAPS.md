# What the Business Cockpit needs and Camunda 8 does not offer

This adapter watches a cluster it does not run. Everything the cockpit shows has to be something the
cluster says out loud, and this file lists the questions it has no answer for on at least one of the
release lines this repository builds.

Each entry says what the cockpit needs, what the cluster offers today, where that can be read, what
it costs somebody looking at the cockpit, and what would close it. A number is handed out once and
never reused. An entry which is closed keeps its number and says so, because a conversation about
"gap 2" outlives the file.

A gap is not a defect of this adapter, and it is not a decision either. What this adapter decided to
do about a gap is in `DECISIONS.md`, and the entries below name the decision they belong to.

## 1. A cancelled instance says nothing before 8.10

**The cockpit needs** to hear that a case was cancelled, so that it stops showing it as open work.

**Camunda 8 offers** the `cancel` execution listener from 8.10 on, on the process element. Lines 8.8
and 8.9 have no listener for it, and the `end` listener of a process does not run when the instance
is terminated, so the cluster hands out no job at all.

**Where it can be read:** the
[execution listeners](https://docs.camunda.io/docs/next/components/concepts/execution-listeners/)
page of the Camunda documentation, which says that `cancel` is supported on the process element only
and that earlier versions reject a deployment using it.

**What it costs** a user of line 8.8 or 8.9: a case an operator cancels stays in the cockpit as it
last was heard of. Its user tasks do disappear, because their `canceling` task listener runs on every
line, so what a person sees is a case with an empty task list which nothing ever closes. The
cockpit's REST endpoint for it exists and is simply never called.

**What would close it** for those lines is nothing this repository can build. A listener is the only
way a cluster tells anybody anything, and the construct is not there. The way out is the release
line: an application which needs cancelled cases in its cockpit runs the 8.10 build. See decision 11
in `DECISIONS.md`.

## 2. An instance holding a user task cannot be cancelled on 8.10.0-alpha5

**The cockpit needs** the cancellation of a case to reach it whatever the case was waiting for, a
user task included, because a user task is what most cases wait at.

**Camunda 8 offers** it on paper. In practice the alpha this repository's 8.10 line pins never gets
there: the `creating` listener job of a Camunda-managed user task is dropped by the REST gateway
while it converts the job, so the task is never finished being created, the instance stays active,
and the cancel listener of the process never runs because the cluster runs it only once every child
element has terminated.

**Where it can be read:** [camunda/camunda#58193](https://github.com/camunda/camunda/issues/58193),
open on `camunda/camunda:8.10.0-alpha5`. It is also why `line-matrix.yaml` leaves 8.10 out of the
nightly matrix and why the VanillaBP Camunda 8 adapter keeps the same alpha out of its pull-request
checks.

**What it costs:** nothing in production, because the line is a preview and no GA cluster carries the
defect. What it costs here is proof. The integration test which cancels a case holding a user task is
written and runs on 8.8 and 8.9; on 8.10 what is proven is the cancellation of a case which waits at
a timer instead (`waiting-process.bpmn`).

**What would close it** is the cluster fix. Once it ships, the 8.10 line comes back into the nightly
matrix and the test which holds a user task proves the same thing there.

## 3. Nobody is named as the initiator of a workflow

**The cockpit needs** to show who started a case, which is a column of its workflow list.

**Camunda 8 offers** nothing this extension can read. A listener job does not carry the user who
started the instance, and the cluster's searchable storage does not hold it either.

**Where it can be read:** the `ActivatedJob` of the Camunda client, and the process-instance records
of the search API. Neither names a user.

**What it costs:** a report of a workflow leaves the initiator empty, so the cockpit shows the column
empty for every Camunda 8 case. An application which knows who started the case can still fill it
from its own `@WorkflowDetailsProvider`.

**What would close it** is the engine recording the authenticated user of a process-instance creation
and handing it out with the instance.
