package io.github.awsopstoolkit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AwsOpsToolkitApplication {
    public static void main(String[] args) {
        SpringApplication.run(AwsOpsToolkitApplication.class, args);
    }
}
