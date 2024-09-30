# Dynamic Configuration

![Build Status](https://img.shields.io/github/actions/workflow/status/descoped/dynamic-configuration/coverage-and-sonar-analysis.yml)
![Latest Tag](https://img.shields.io/github/v/tag/descoped/dynamic-configuration)
![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=descoped_dynamic-configuration&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=descoped_dynamic-configuration) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=descoped_dynamic-configuration&metric=coverage)](https://sonarcloud.io/summary/new_code?id=descoped_dynamic-configuration)
[![Snyk Security Score](https://snyk.io/test/github/descoped/dynamic-configuration/badge.svg)](https://snyk.io/test/github/descoped/dynamic-configuration)

The Configuration library is a clean (no external dependencies) Java implementation inspired by and partly based on work
found in the Constretto library: https://github.com/constretto/constretto-core

This library contains a simple configuration loading and override mechanism capable of reading properties files from 
file-system or classpath and also ability to read configuration from environment and java system properties.


Example:
```properties
# application.properties
host=localhost
port=28282
accesslog.enabled=true
```
```java
// Sample Java application using configuration library

public class MyApp {

    public static void main(String[] args) {

        DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
                .propertiesResource("application.properties")
                .propertiesResource("application_override.properties")
                .environment("MYAPP_")
                .systemProperties()
                .build();

        String host = configuration.evaluateToString("host");
        int port = configuration.evaluateToInt("port");
        boolean accessLogEnabled = configuration.evaluateToBoolean("accesslog.enabled");

        new MyWebServer(host, port, accessLogEnabled);
    }
}
```
