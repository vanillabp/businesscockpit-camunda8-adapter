package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.List;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/**
 * The business case of the test: what the workflow is about and what a details provider
 * changes.
 * <p>
 * It carries a version attribute, and that is what an application with a writing details
 * provider is meant to do. The provider runs while a report is dispatched, in the transaction
 * of that dispatch, so it reads this case long before it writes it back. An application
 * changing the same case in that window would otherwise lose its change without a word: the
 * dispatch writes every field it holds, the older reading among them. With the version
 * attribute the later of the two writers reads a conflict instead. The application answers it
 * by repeating its transaction, and the dispatch answers it by leaving the outbox entry
 * unfinished, which sends the report again from a fresh reading of the case.
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

  private String note;

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

  public String getNote() {

    return note;

  }

  public void setNote(
      final String note) {

    this.note = note;

  }

}
