# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A Spring Boot 4.1.1 application (Java 17) using Maven. Currently a fresh scaffold with no application logic beyond the auto-configured main class.

- **GroupId**: `com`, **ArtifactId**: `Agentdemo007`
- **Main class**: `com.agentdemo007.Agentdemo007Application`
- **Source layout**: standard Maven (`src/main/java`, `src/main/resources`, `src/test/java`)

## Build & Run

The project ships with the Maven Wrapper (`mvnw`), so no local Maven install is needed.

| Task | Command |
|------|---------|
| Build | `./mvnw clean package` |
| Run app | `./mvnw spring-boot:run` |
| Run all tests | `./mvnw test` |
| Run a single test | `./mvnw -Dtest=Agentdemo007ApplicationTests test` |
| Run a single test method | `./mvnw -Dtest=Agentdemo007ApplicationTests#contextLoads test` |
| Skip tests on build | `./mvnw -DskipTests package` |
| Compile only | `./mvnw compile` |
| IDE import | Use "Import Maven Project" on `pom.xml` |

## Dependencies

Currently only the Spring Boot starter and starter-test are declared. Any new dependency (web, data, security, etc.) should be added as a `<dependency>` block under `pom.xml`.

## Notes

- Java version is pinned to 17 in `<properties>`.
- The POM contains empty `<license>`/`<developers>`/`<scm>` overrides inherited from `spring-boot-starter-parent`; leave them unless you intentionally switch parents.
- The only configuration file is `application.properties` (`spring.application.name=Agentdemo007`).
