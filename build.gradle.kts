plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "urlchecker"
version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

application {
    mainClass.set("urlchecker.MainKt")
    // Keep the launcher variable locale-independent (Turkish i uppercasing breaks
    // the default project-name-based launcher on systems using tr_TR.UTF-8).
    applicationName = "urlchecker"
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
