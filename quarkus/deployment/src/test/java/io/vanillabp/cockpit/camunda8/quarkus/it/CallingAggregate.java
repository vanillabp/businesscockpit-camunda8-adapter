package io.vanillabp.cockpit.camunda8.quarkus.it;

/**
 * The business case of a workflow which has a step done by a called process. One case, two
 * process instances: the cockpit is to show the case and not the step.
 */
public class CallingAggregate {

  private Long id;

  private String customer;

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

}
