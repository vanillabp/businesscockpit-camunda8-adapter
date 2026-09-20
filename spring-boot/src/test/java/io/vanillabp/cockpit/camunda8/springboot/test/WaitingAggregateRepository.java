package io.vanillabp.cockpit.camunda8.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the cases of the waiting workflow live.
 */
public interface WaitingAggregateRepository extends JpaRepository<WaitingAggregate, Long> {
}
