package com.neowadaeum;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Tag("container")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NeowadaeumBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
