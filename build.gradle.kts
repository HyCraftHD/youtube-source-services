plugins {
    `java-library`
}

group = "net.hycrafthd"
version = "0.1.0-SNAPSHOT"

java {
    // Minecraft versions before 1.17 still run on Java 8
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // On newer JDKs, --release also rejects APIs that do not exist in Java 8
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Downloads Deno and the services and runs them on this platform."
    group = "verification"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)

    useJUnitPlatform {
        includeTags("integration")
    }

    // Shared between all integration tests, so Deno and the services are only downloaded once per build directory.
    // Can be moved with YOUTUBE_SOURCE_SERVICES_TEST_DIR, e.g. off a slow network or WSL-mounted drive.
    val testDir = System.getenv("YOUTUBE_SOURCE_SERVICES_TEST_DIR")
        ?: layout.buildDirectory.dir("integration-services").get().asFile.absolutePath
    systemProperty("youtube_source_services.test.dir", testDir)

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
