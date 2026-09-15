package io.vanillabp.cockpit.camunda8.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the cases of the calling workflow live.
 */
public interface CallingAggregateRepository extends JpaRepository<CallingAggregate, Long> {
}
