package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.StartEvent;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitListeners;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the Business Cockpit writes into a Camunda 8 model.
 * <p>
 * The user-task half of it is a compatibility promise and is therefore measured against Version
 * 1's own code rather than against a description of it: the two have to produce the same bytes,
 * because an upgrading application which deploys different bytes gets a new process version and
 * leaves its running workflows behind on the old one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitListenersTest {

  private static final String PROCESS_ID = "CockpitProcess";

  private static final String FORM_REFERENCE = "approve";

  private static BpmnModelInstance model(
      final String userTaskExtensions) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="Started" />
            <bpmn:userTask id="Approve">
              <bpmn:extensionElements>
        %s
              </bpmn:extensionElements>
            </bpmn:userTask>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS_ID, userTaskExtensions);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /** A model as VanillaBP's own Camunda 8 adapter leaves it, which is what the cockpit gets. */
  private static BpmnModelInstance aModelVanillaBpWired(
      final String userTaskExtensions) {

    final var bpmn = model(userTaskExtensions);
    Camunda8TaskWiring.userTasksOf(bpmn, PROCESS_ID, "mod", "x.bpmn");
    return bpmn;

  }

  private static List<ZeebeTaskListener> taskListenersOf(
      final BpmnModelInstance model) {

    return List
        .copyOf(
            model
                .getModelElementsByType(UserTask.class)
                .iterator()
                .next()
                .getSingleExtensionElement(ZeebeTaskListeners.class)
                .getTaskListeners());

  }

  private static List<ZeebeExecutionListener> executionListenersOf(
      final BpmnModelInstance model,
      final String elementId) {

    final var element = (BaseElement) model.getModelElementById(elementId);
    final var listeners = element.getSingleExtensionElement(ZeebeExecutionListeners.class);
    return listeners == null
        ? List.of()
        : List.copyOf(listeners.getExecutionListeners());

  }

  private static void addCockpitTaskListeners(
      final BpmnModelInstance model) {

    Camunda8CockpitListeners
        .addUserTaskListeners(
            (UserTask) model.getModelElementById("Approve"),
            Camunda8CockpitListeners.listenerTypeOf(FORM_REFERENCE));

  }

  /**
   * Both models are wired by VanillaBP's Camunda 8 adapter first, which is not a convenience of
   * the test but the situation being measured. That adapter puts a <code>creating</code> and a
   * <code>canceling</code> listener on every user task it claims, and with them the
   * <code>zeebe:taskListeners</code> container. So by the time either the Version 1 code or this
   * extension runs, the container is always there. Version 1 had a second branch for a task
   * which carried none, and it wrote a different order (<code>creating</code>,
   * <code>completing</code>, <code>canceling</code>). That branch was unreachable behind the
   * adapter then and is unreachable now, which is why the byte-identity is measured on the
   * branch which did run. What this extension produces for a task without a container is
   * pinned separately by {@link #aTaskWithoutAContainerGetsTheOrderThisExtensionDocuments()}.
   */
  @Test
  @DisplayName("The task listeners are byte-identical to the ones Version 1 wrote")
  public void taskListenersAreByteIdenticalToVersion1() {

    final var extensions = """
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="%s" />""".formatted(FORM_REFERENCE);

    final var wiredByVersion1 = aModelVanillaBpWired(extensions);
    V1Listeners
        .addTaskListenersToBpmnModel(wiredByVersion1.getModelElementById("Approve"), FORM_REFERENCE);

    final var wiredByTheExtension = aModelVanillaBpWired(extensions);
    addCockpitTaskListeners(wiredByTheExtension);

    assertEquals(
        Bpmn.convertToString(wiredByVersion1),
        Bpmn.convertToString(wiredByTheExtension));

  }

  @Test
  @DisplayName("A model carrying the modeller's own listeners is byte-identical to Version 1 as well")
  public void taskListenersOfAModelWithCustomListenersMatchVersion1() {

    final var extensions = """
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="%s" />
        <zeebe:taskListeners>
          <zeebe:taskListener eventType="creating" type="custom-creating" />
          <zeebe:taskListener eventType="assigning" type="custom-assigning" />
        </zeebe:taskListeners>""".formatted(FORM_REFERENCE);

    final var wiredByVersion1 = aModelVanillaBpWired(extensions);
    V1Listeners
        .addTaskListenersToBpmnModel(wiredByVersion1.getModelElementById("Approve"), FORM_REFERENCE);

    final var wiredByTheExtension = aModelVanillaBpWired(extensions);
    addCockpitTaskListeners(wiredByTheExtension);

    assertEquals(
        Bpmn.convertToString(wiredByVersion1),
        Bpmn.convertToString(wiredByTheExtension));

  }

  @Test
  @DisplayName("A user task the adapter claimed always carries the listener container before this extension runs")
  public void theAdapterLeavesTheListenerContainerBehind() {

    final var withoutAnyListener = aModelVanillaBpWired("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="%s" />""".formatted(FORM_REFERENCE));
    final var withTheModellersOwn = aModelVanillaBpWired("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="%s" />
        <zeebe:taskListeners>
          <zeebe:taskListener eventType="creating" type="custom-creating" />
        </zeebe:taskListeners>""".formatted(FORM_REFERENCE));

    // this is what makes the byte-identity test measure the whole promise: Version 1's other
    // branch, the one for a task carrying no listeners at all, cannot be reached behind the
    // adapter, because the adapter wired its own two listeners before the cockpit sees the model
    assertEquals(
        List.of("creating", "canceling"),
        taskListenersOf(withoutAnyListener)
            .stream()
            .map(listener -> listener.getEventType().toString())
            .toList());
    assertEquals(
        List.of("creating", "custom-creating", "canceling"),
        taskListenersOf(withTheModellersOwn)
            .stream()
            .map(
                listener -> listener.getType().startsWith("io.vanillabp.userTask:")
                    ? listener.getEventType().toString()
                    : listener.getType())
            .toList());

  }

  @Test
  @DisplayName("A user task carrying no listeners at all gets creating, canceling and completing, in that order")
  public void aTaskWithoutAContainerGetsTheOrderThisExtensionDocuments() {

    // NOT what the extension meets in a deployment. The adapter wires first and leaves the
    // container behind, see theAdapterLeavesTheListenerContainerBehind(). It is written down
    // because it is the one case in which this extension and Version 1 would differ: Version 1
    // inserted creating, completing, canceling here, this extension inserts each listener
    // behind everything it has to run after, which reads creating, canceling, completing
    final var bpmn = model("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="%s" />""".formatted(FORM_REFERENCE));
    addCockpitTaskListeners(bpmn);

    assertEquals(
        List
            .of(
                ZeebeTaskListenerEventType.creating, ZeebeTaskListenerEventType.canceling,
                ZeebeTaskListenerEventType.completing),
        taskListenersOf(bpmn).stream().map(ZeebeTaskListener::getEventType).toList());

  }

  @Test
  @DisplayName("The cockpit's creating listener runs after every other creating listener, its other two run last")
  public void taskListenersAreOrderedBehindWhatTheyReportAbout() {

    final var bpmn = aModelVanillaBpWired("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="%s" />
        <zeebe:taskListeners>
          <zeebe:taskListener eventType="creating" type="custom-creating" />
          <zeebe:taskListener eventType="assigning" type="custom-assigning" />
        </zeebe:taskListeners>""".formatted(FORM_REFERENCE));
    addCockpitTaskListeners(bpmn);

    final var cockpit = Camunda8CockpitListeners.listenerTypeOf(FORM_REFERENCE);
    final var listeners = taskListenersOf(bpmn);
    assertEquals(
        List
            .of(
                "io.vanillabp.userTask:"
                    + FORM_REFERENCE,
                "custom-creating", cockpit,
                "custom-assigning", "io.vanillabp.userTask:"
                    + FORM_REFERENCE,
                cockpit, cockpit),
        listeners.stream().map(ZeebeTaskListener::getType).toList());
    assertEquals(
        List
            .of(
                ZeebeTaskListenerEventType.creating, ZeebeTaskListenerEventType.creating,
                ZeebeTaskListenerEventType.creating, ZeebeTaskListenerEventType.assigning,
                ZeebeTaskListenerEventType.canceling, ZeebeTaskListenerEventType.canceling,
                ZeebeTaskListenerEventType.completing),
        listeners.stream().map(ZeebeTaskListener::getEventType).toList());

  }

  @Test
  @DisplayName("Every listener of this extension carries no retries, so a failure is an incident")
  public void everyListenerCarriesNoRetries() {

    final var bpmn = aModelVanillaBpWired("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="%s" />""".formatted(FORM_REFERENCE));
    addCockpitTaskListeners(bpmn);
    final var process = Camunda8CockpitListeners.processOf(bpmn, PROCESS_ID).orElseThrow();
    final var workflowType = Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID);
    Camunda8CockpitListeners.addProcessListener(process, workflowType);
    Camunda8CockpitListeners
        .addStartEventListener(
            Camunda8CockpitListeners.startEventsOf(process).getFirst(), workflowType);

    final var cockpit = Camunda8CockpitListeners.listenerTypeOf(FORM_REFERENCE);
    taskListenersOf(bpmn)
        .stream()
        .filter(listener -> cockpit.equals(listener.getType()))
        .forEach(listener -> assertEquals("0", listener.getRetries()));
    executionListenersOf(bpmn, PROCESS_ID)
        .forEach(listener -> assertEquals("0", listener.getRetries()));
    executionListenersOf(bpmn, "Started")
        .forEach(listener -> assertEquals("0", listener.getRetries()));

  }

  @Test
  @DisplayName("A workflow is reported as begun by an end listener of the start event, not by a start listener of the process")
  public void aWorkflowBeginsAtItsStartEvent() {

    final var bpmn = aModelVanillaBpWired("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="%s" />""".formatted(FORM_REFERENCE));
    final var process = Camunda8CockpitListeners.processOf(bpmn, PROCESS_ID).orElseThrow();
    final var workflowType = Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID);

    assertTrue(
        Camunda8CockpitListeners
            .addStartEventListener(
                Camunda8CockpitListeners.startEventsOf(process).getFirst(), workflowType));
    assertTrue(Camunda8CockpitListeners.addProcessListener(process, workflowType));

    final var atTheStartEvent = executionListenersOf(bpmn, "Started");
    assertEquals(1, atTheStartEvent.size());
    assertEquals(ZeebeExecutionListenerEventType.end, atTheStartEvent.getFirst().getEventType());
    assertEquals(workflowType, atTheStartEvent.getFirst().getType());

    final var atTheProcess = executionListenersOf(bpmn, PROCESS_ID);
    assertEquals(1, atTheProcess.size());
    assertEquals(ZeebeExecutionListenerEventType.end, atTheProcess.getFirst().getEventType());
    assertEquals(workflowType, atTheProcess.getFirst().getType());

  }

  @Test
  @DisplayName("Only the process' own start events are wired, not the ones inside a subprocess")
  public void onlyTheProcessOwnStartEventsAreWired() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="Started" />
            <bpmn:subProcess id="Sub">
              <bpmn:startEvent id="SubStarted" />
            </bpmn:subProcess>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS_ID);
    final var bpmn = Bpmn
        .readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

    final var process = Camunda8CockpitListeners.processOf(bpmn, PROCESS_ID).orElseThrow();

    assertEquals(
        List.of("Started"),
        Camunda8CockpitListeners.startEventsOf(process).stream().map(StartEvent::getId).toList());

  }

  @Test
  @DisplayName("Wiring the same model again changes nothing")
  public void rewiringChangesNothing() {

    final var bpmn = aModelVanillaBpWired("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="%s" />""".formatted(FORM_REFERENCE));
    final var process = Camunda8CockpitListeners.processOf(bpmn, PROCESS_ID).orElseThrow();
    final var workflowType = Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID);
    final var startEvent = Camunda8CockpitListeners.startEventsOf(process).getFirst();

    addCockpitTaskListeners(bpmn);
    Camunda8CockpitListeners.addStartEventListener(startEvent, workflowType);
    Camunda8CockpitListeners.addProcessListener(process, workflowType);
    final var afterTheFirstWiring = Bpmn.convertToString(bpmn);

    assertFalse(
        Camunda8CockpitListeners
            .addUserTaskListeners(
                (UserTask) bpmn.getModelElementById("Approve"),
                Camunda8CockpitListeners.listenerTypeOf(FORM_REFERENCE)));
    assertFalse(Camunda8CockpitListeners.addStartEventListener(startEvent, workflowType));
    assertFalse(Camunda8CockpitListeners.addProcessListener(process, workflowType));

    assertEquals(afterTheFirstWiring, Bpmn.convertToString(bpmn));

  }

  @Test
  @DisplayName("Under 'use-prefix' the listener types carry the identifiers the cluster knows")
  public void listenerTypesFollowTheIdentifiersTheClusterKnows() {

    // what the model carries after name-clash avoidance rewrote it: the extension reads the
    // identifiers out of the model and never builds a prefix of its own
    assertEquals(
        "io.vanillabp.businesscockpit:mod__CockpitProcess__approve",
        Camunda8CockpitListeners.listenerTypeOf("mod__CockpitProcess__approve"));
    assertEquals(
        "mod__CockpitProcess__approve",
        Camunda8CockpitListeners
            .identifierOf("io.vanillabp.businesscockpit:mod__CockpitProcess__approve"));

  }

  @Test
  @DisplayName("A job type of somebody else is left as it is rather than cut into")
  public void anUnknownJobTypeIsNotCutInto() {

    assertEquals("io.camunda.zeebe:userTask", Camunda8CockpitListeners.identifierOf("io.camunda.zeebe:userTask"));

  }

  @Test
  @DisplayName("A process this file does not hold is answered as such")
  public void aProcessOfAnotherFileIsNotFound() {

    final var bpmn = model("<zeebe:userTask />");

    assertTrue(Camunda8CockpitListeners.processOf(bpmn, "SomethingElse").isEmpty());
    assertNull(Bpmn.convertToString(bpmn).contains("businesscockpit") ? "unexpected" : null);

  }

}
