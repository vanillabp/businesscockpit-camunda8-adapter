package io.vanillabp.cockpit.camunda8.quarkus.it;

import java.util.List;

/** The business case of the test: what the workflow is about. */
public class TestAggregate {

  private Long id;

  private String customer;

  private String note;

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
