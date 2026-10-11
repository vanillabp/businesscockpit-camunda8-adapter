package io.vanillabp.cockpit.camunda8.quarkus.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Where the test application keeps its workflow aggregates. They are in memory, not in a
 * database, because the test is about the extension and not about persistence.
 */
@ApplicationScoped
public class TestAggregatePersistence implements AggregatePersistenceAware<TestAggregate> {

  private final Map<Long, TestAggregate> aggregates = new ConcurrentHashMap<>();

  private final AtomicLong ids = new AtomicLong();

  @Override
  public Class<TestAggregate> getAggregateClass() {

    return TestAggregate.class;

  }

  @Override
  public TestAggregate save(
      final TestAggregate aggregate) {

    if (aggregate.getId() == null) {
      aggregate.setId(ids.incrementAndGet());
    }
    aggregates.put(aggregate.getId(), aggregate);
    return aggregate;

  }

  @Override
  public TestAggregate loadById(
      final Object aggregateId) {

    return aggregates.get(Long.valueOf(String.valueOf(aggregateId)));

  }

  @Override
  public Object getAggregateId(
      final TestAggregate aggregate) {

    return aggregate.getId();

  }

  /**
   * The name under which a Camunda 8 workflow carries the aggregate's id. A persistence
   * framework answers this from its mapping. A map has to say it, and every read of a workflow of
   * this application by its business case depends on it.
   */
  @Override
  public String getAggregateIdName() {

    return "id";

  }

  /**
   * @param id The aggregate's id
   * @return What is stored under it
   */
  public TestAggregate byId(
      final Long id) {

    return aggregates.get(id);

  }

}
