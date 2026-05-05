# Marco 1: Projeto Base

> **Entregavel:** Projeto Maven compila, main class existe, dependencias resolvidas.

**Parent plan:** `2026-04-30-pod-crash-notifier.md`

---

## Step 1.1: pom.xml + OperatorApplication

- [ ] Criar `pom.xml` com dependencias: JOSDK 5.x, Fabric8, Jackson, Logback, JUnit 5, Mockito, crd-generator-apt

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.crashnotifier</groupId>
    <artifactId>operator-crash-notifier</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <josdk.version>5.0.1</josdk.version>
        <fabric8.version>7.0.1</fabric8.version>
        <jackson.version>2.17.0</jackson.version>
        <junit.version>5.10.2</junit.version>
        <mockito.version>5.11.0</mockito.version>
        <slf4j.version>2.0.12</slf4j.version>
        <logback.version>1.5.3</logback.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.javaoperatorsdk</groupId>
            <artifactId>operator-framework</artifactId>
            <version>${josdk.version}</version>
        </dependency>
        <dependency>
            <groupId>io.fabric8</groupId>
            <artifactId>kubernetes-client</artifactId>
            <version>${fabric8.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>${slf4j.version}</version>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>${logback.version}</version>
        </dependency>
        <dependency>
            <groupId>io.fabric8</groupId>
            <artifactId>crd-generator-apt</artifactId>
            <version>${fabric8.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>io.crashnotifier.OperatorApplication</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] Criar `src/main/java/io/crashnotifier/OperatorApplication.java` (main class basica, sem reconciler ainda)

```java
package io.crashnotifier;

import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperatorApplication {
    private static final Logger log = LoggerFactory.getLogger(OperatorApplication.class);

    public static void main(String[] args) {
        log.info("Starting Pod Crash Notifier Operator");
        Operator operator = new Operator();
        operator.installShutdownHook();
        operator.start();
        log.info("Operator started successfully");
    }
}
```

- [ ] Criar `src/main/resources/logback.xml`

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <logger name="io.crashnotifier" level="INFO"/>
    <logger name="io.javaoperatorsdk" level="INFO"/>
    <logger name="io.fabric8" level="WARN"/>
    <root level="WARN">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

## Verificacao

- [ ] Rodar `mvn compile` -- deve passar

## Commit

- [ ] `feat: scaffold Maven project with JOSDK dependencies`
