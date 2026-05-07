# keycloak-provisioning

[![CI](https://github.com/cronn/keycloak-provisioning/actions/workflows/gradle.yml/badge.svg)](https://github.com/cronn/keycloak-provisioning/actions/workflows/gradle.yml)
[![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

## Build

```bash
./gradlew build
```

## Code formatting

This project uses [Spotless](https://github.com/diffplug/spotless) with
[Google Java Format](https://github.com/google/google-java-format).

```bash
./gradlew spotlessApply
```

## Updating dependencies

Dependency versions are locked. To refresh them:

```bash
./updateDependencies.sh
```

## Release

```bash
./release.sh
```