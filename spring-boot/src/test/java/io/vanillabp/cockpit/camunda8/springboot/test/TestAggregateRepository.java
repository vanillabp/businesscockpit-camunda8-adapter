package io.vanillabp.cockpit.camunda8.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the workflow aggregates of the test live. VanillaBP loads and saves through it, and so
 * does the extension whenever a details provider runs.
 */
public interface TestAggregateRepository extends JpaRepository<TestAggregate, Long> {
}
