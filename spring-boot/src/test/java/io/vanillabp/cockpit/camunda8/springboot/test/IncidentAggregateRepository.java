package io.vanillabp.cockpit.camunda8.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the cases of the retried workflow are stored.
 */
public interface RetriedAggregateRepository extends JpaRepository<RetriedAggregate, Long> {
}
