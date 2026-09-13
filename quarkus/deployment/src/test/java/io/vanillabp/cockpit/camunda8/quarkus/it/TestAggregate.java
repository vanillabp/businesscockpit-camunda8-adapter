package io.vanillabp.cockpit.camunda8.quarkus.it;

import java.util.List;

/**
 * The business case of the test: what the workflow is about.
 * <p>
 * It carries no version attribute, unlike the entity of the Spring Boot test, and it does not
 * need one: {@link TestAggregatePersistence} is a map which hands out the very object it holds,
 * so a details provider changing the case changes the one copy there is. There is no older
 * reading anybody could write back over a newer one.
 */
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
