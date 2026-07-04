package com.repomind.backend;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Requires Postgres, Redis, and Kafka — only runnable inside Docker with full infrastructure.
// All other tests in this project are pure unit tests with no infrastructure dependencies.
@Disabled("Integration test — needs Docker infrastructure")
@SpringBootTest
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
