package io.vanillabp.cockpit.camunda8.quarkus.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Where the cases of the process with an aggregate of its own live, in memory like the others.
 */
@ApplicationScoped
public class OwnCaseAggregatePersistence implements AggregatePersistenceAware<OwnCaseAggregate> {

  private final Map<String, OwnCaseAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<OwnCaseAggregate> getAggregateClass() {

    return OwnCaseAggregate.class;

  }

  @Override
  public OwnCaseAggregate save(
      final OwnCaseAggregate aggregate) {

    aggregates.put(aggregate.getCaseNumber(), aggregate);
    return aggregate;

  }

  @Override
  public OwnCaseAggregate loadById(
      final Object aggregateId) {

    return aggregates.get(String.valueOf(aggregateId));

  }

  @Override
  public Object getAggregateId(
      final OwnCaseAggregate aggregate) {

    return aggregate.getCaseNumber();

  }

  /** The name a Camunda 8 workflow carries the aggregate's id under. */
  @Override
  public String getAggregateIdName() {

    return "caseNumber";

  }

}
