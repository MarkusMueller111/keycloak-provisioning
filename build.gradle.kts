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
    jacoco
    `maven-publish`
    signing
    id("io.spring.dependency-management") version "latest.release"
    id("org.jreleaser") version "latest.release"
    id("com.diffplug.spotless") version "latest.release"
}

group = "de.cronn"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencyManagement {
    imports {
        mavenBom("org.junit:junit-bom:latest.release")
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core:latest.release")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

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
    maxHeapSize = "256m"
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
                        id = "cronn"
                        name = "cronn GmbH"
                        email = "info@cronn.de"
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

tasks.wrapper {
    gradleVersion = "9.5.0"
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
