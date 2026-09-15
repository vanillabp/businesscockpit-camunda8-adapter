package io.vanillabp.cockpit.camunda8.quarkus.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Where the cases of the calling workflow live, in memory like the other one.
 */
@ApplicationScoped
public class CallingAggregatePersistence implements AggregatePersistenceAware<CallingAggregate> {

  private final Map<Long, CallingAggregate> aggregates = new ConcurrentHashMap<>();

  private final AtomicLong ids = new AtomicLong();

  @Override
  public Class<CallingAggregate> getAggregateClass() {

    return CallingAggregate.class;

  }

  @Override
  public CallingAggregate save(
      final CallingAggregate aggregate) {

    if (aggregate.getId() == null) {
      aggregate.setId(ids.incrementAndGet());
    }
    aggregates.put(aggregate.getId(), aggregate);
    return aggregate;

  }

  @Override
  public CallingAggregate loadById(
      final Object aggregateId) {

    return aggregates.get(Long.valueOf(String.valueOf(aggregateId)));

  }

  @Override
  public Object getAggregateId(
      final CallingAggregate aggregate) {

    return aggregate.getId();

  }

  /** The name a Camunda 8 workflow carries the aggregate's id under. */
  @Override
  public String getAggregateIdName() {

    return "id";

  }

  /**
   * @param id A case of this application
   * @return What is stored for it
   */
  public CallingAggregate byId(
      final Long id) {

    return aggregates.get(id);

  }

}
