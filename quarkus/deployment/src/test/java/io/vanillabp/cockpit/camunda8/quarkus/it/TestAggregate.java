package io.vanillabp.cockpit.camunda8.quarkus.it;

import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The business case of the test: what the workflow is about.
 * <p>
 * Unlike the entity of the Spring Boot test, it has no version attribute, and it needs none.
 * {@link TestAggregatePersistence} is a map which hands out the same object it holds, so a details
 * provider which changes the case changes the only copy there is. Nobody holds an older state
 * which could be written back over a newer one.
 * <p>
 * The signers never reach the BPMS. They belong to the case which this application keeps, and no
 * model of this repository reads any value of the aggregate. Sending them would fill a process
 * variable which nobody ever opens. {@link NoSyncWithBPMS} below says this.
 * <p>
 * The customer and the note are still sent, so the test application also shows a workflow which
 * shares everything it has. The <code>allow-full-sync-with-bpms</code> line of this workflow stays
 * as well, although the annotation already makes the permission unnecessary here. It costs
 * nothing, and the other processes of the test application need their own line, because the
 * permission is not inherited.
 */
public class TestAggregate {

  private Long id;

  private String customer;

  private String note;

  /** Who has to sign the case, kept by this application and read by no model. */
  @NoSyncWithBPMS
  private List<String> signers;

  public Long getId() {

    return id;

  }

  public void setId(
      final Long id) {

    this.id = id;

  }

  public String getCustomer() {

    return customer;

  }

  public void setCustomer(
      final String customer) {

    this.customer = customer;

  }

  public List<String> getSigners() {

    return signers;

  }

  public void setSigners(
      final List<String> signers) {

    this.signers = signers;

  }

  public String getNote() {

    return note;

  }

  public void setNote(
      final String note) {

    this.note = note;

  }

}
