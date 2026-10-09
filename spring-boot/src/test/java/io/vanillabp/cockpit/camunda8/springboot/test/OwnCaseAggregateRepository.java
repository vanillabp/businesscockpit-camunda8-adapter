package io.vanillabp.cockpit.camunda8.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the cases of the process with an aggregate of its own live.
 */
public interface OwnCaseAggregateRepository extends JpaRepository<OwnCaseAggregate, String> {
}
