package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.List;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/**
 * The business case of the test: what the workflow is about, and what a details provider reads
 * to describe it.
 * <p>
 * It carries a version attribute, which is what an application does where a case may have a
 * second writer. The cockpit is not one of them. A details provider is asked a question and
 * answers it, it never writes this case, and the report of an event is built while that event is
 * being observed rather than hours later. The attribute is here so that a provider which
 * accidentally wrote would read a conflict instead of overwriting the application without a word.
 */
@Entity
public class TestAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** What the persistence increments per write, and what a second writer runs into. */
  @Version
  private Long version;

  private String customer;

  @ElementCollection
  private List<String> signers;

  public Long getId() {

    return id;

  }

  public void setId(
      final Long id) {

    this.id = id;

  }

  public Long getVersion() {

    return version;

  }

  public void setVersion(
      final Long version) {

    this.version = version;

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

}
