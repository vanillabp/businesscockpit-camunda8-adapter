package io.vanillabp.cockpit.camunda8.quarkus.it;

/**
 * The business case of a workflow which has steps done by called processes. One case, several
 * process instances: the cockpit should show the case and not the steps.
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
