package com.neowadaeum;

import org.springframework.boot.SpringApplication;

public class TestNeowadaeumBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(NeowadaeumBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
