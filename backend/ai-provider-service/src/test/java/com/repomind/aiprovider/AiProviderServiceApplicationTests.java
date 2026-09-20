package com.repomind.aiprovider;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Stateless service, but still needs its own port etc. wired up cleanly — kept
// consistent with the other services' disabled context-load smoke test.
@Disabled("Integration test — run inside Docker alongside the other services")
@SpringBootTest
class AiProviderServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
