package io.vanillabp.cockpit.camunda8.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the cases of the interrupted workflow live.
 */
public interface InterruptedAggregateRepository extends JpaRepository<InterruptedAggregate, Long> {
}
