buildscript {
    repositories {
        mavenCentral()
    }
    dependencyLocking {
        lockAllConfigurations()
    }
}

plugins {
    `java-library`
    `java-test-fixtures`
    jacoco
    `maven-publish`
    signing
    id("org.jreleaser") version "latest.release"
    id("com.diffplug.spotless") version "latest.release"
}

group = "de.cronn"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    // Jackson 2, matching keycloak-admin-client: its representations declare Jackson 2 JsonNode
    // fields that Jackson 3 cannot serialize.
    implementation(platform("com.fasterxml.jackson:jackson-bom:latest.release"))
    testFixturesApi(platform("com.fasterxml.jackson:jackson-bom:latest.release"))
    testImplementation(platform("org.junit:junit-bom:latest.release"))

    // Pinned rather than latest.release: this is api-scoped, so the version reaches every
    // consumer's classpath and is this library's statement of which Keycloak it supports.
    // TODO Switch to keycloak-admin-client-jackson3, and Jackson 3 above, once it is released.
    api("org.keycloak:keycloak-admin-client:26.0.12")

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("io.github.java-diff-utils:java-diff-utils:latest.release")
    implementation("org.apache.commons:commons-lang3:latest.release")

    implementation("de.cronn:commons-lang:latest.release")

    implementation("org.slf4j:slf4j-api:latest.release")

    testImplementation(platform("org.testcontainers:testcontainers-bom:latest.release"))
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core:latest.release")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation(testFixtures(project))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("ch.qos.logback:logback-classic:latest.release")

    testFixturesApi("com.fasterxml.jackson.core:jackson-databind")
    testFixturesApi("de.cronn:validation-file-assertions:latest.release")

    components {
        all {
            if (id.version.matches(Regex("(?i).+([-.])(CANDIDATE|RC|BETA|ALPHA|CR\\d+|M\\d+).*"))) {
                status = "milestone"
            }
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
    dependsOn(tasks.test)
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier = "sources"
    from(sourceSets["main"].allSource)
    dependsOn(tasks.named("classes"))
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier = "javadoc"
    from(tasks.javadoc.get().destinationDir)
    dependsOn(tasks.javadoc)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            from(components["java"])

            artifact(sourcesJar)
            artifact(javadocJar)

            pom {
                name = project.name
                description = "Keycloak Provisioning"
                url = "https://github.com/cronn/keycloak-provisioning"

                licenses {
                    license {
                        name = "The Apache Software License, Version 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }

                developers {
                    developer {
                        id = "markus.mueller"
                        name = "Markus Müller"
                        email = "markus.mueller@cronn.de"
                    }
                }

                scm {
                    url = "https://github.com/cronn/keycloak-provisioning"
                }
            }

            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["mavenJava"])
}

tasks.withType<Sign> {
    onlyIf { !project.hasProperty("skipSigning") }
}

tasks.wrapper {
    gradleVersion = "9.8.1"
    distributionType = Wrapper.DistributionType.ALL
}

jreleaser {
    signing {
        setActive("NEVER")
    }
    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    setActive("RELEASE")
                    sign = false
                    url = "https://central.sonatype.com/api/v1/publisher"
                    stagingRepository(
                        layout.buildDirectory
                            .dir("staging-deploy")
                            .get()
                            .asFile.path,
                    )
                }
            }
        }
    }
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
