package io.vanillabp.cockpit.camunda8.springboot.test;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * The business case of a process the calling workflow starts by a call activity, but which works
 * on an aggregate of its own. The id attribute has a name of its own, so the called instance
 * carries both ids side by side: its own and the one the cluster copied over from the caller.
 */
@Entity
public class OwnCaseAggregate {

  @Id
  private String caseNumber;

  public String getCaseNumber() {

    return caseNumber;

  }

  public void setCaseNumber(
      final String caseNumber) {

    this.caseNumber = caseNumber;

  }

}
