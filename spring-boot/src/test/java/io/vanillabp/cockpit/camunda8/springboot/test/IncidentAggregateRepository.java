package io.vanillabp.cockpit.camunda8.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the cases of the workflow with the failing details provider are stored.
 */
public interface IncidentAggregateRepository extends JpaRepository<IncidentAggregate, Long> {
}
