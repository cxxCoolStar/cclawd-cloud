package ai.openagent.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "ai.openagent")
@ConfigurationPropertiesScan(basePackages = "ai.openagent")
public class OpenAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenAgentApplication.class, args);
    }
}

