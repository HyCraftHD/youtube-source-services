import org.ajoberstar.grgit.Grgit

plugins {
    `java-library`
    `maven-publish`
    id("org.ajoberstar.grgit") version "5.2.0"
}

val (gitVersion, release) = versionFromGit()
logger.lifecycle("Version: $gitVersion (release: $release)")

group = "net.hycrafthd.lavaplayer-extras.youtube-source-services"
version = gitVersion

java {
    // Minecraft versions before 1.17 still run on Java 8
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
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

tasks.javadoc {
    // Missing @param and @return tags should not fail the build
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("youtube-source-services")
                description.set("Runs yt-cipher and webpo-generator as local Deno processes for youtube-source")
                url.set("https://github.com/HyCraftHD/youtube-source-services")
                scm {
                    url.set("https://github.com/HyCraftHD/youtube-source-services")
                    connection.set("scm:git:https://github.com/HyCraftHD/youtube-source-services.git")
                }
            }
        }
    }

    val mavenUsername = findProperty("MAVEN_USERNAME") as String?
    val mavenPassword = findProperty("MAVEN_PASSWORD") as String?
    if (!mavenUsername.isNullOrEmpty() && !mavenPassword.isNullOrEmpty()) {
        repositories {
            val snapshots = "https://repo-snapshots.u-team.info"
            val releases = "https://repo.u-team.info"

            maven(if (release) releases else snapshots) {
                credentials {
                    username = mavenUsername
                    password = mavenPassword
                }
            }
        }
    } else {
        logger.lifecycle("Not publishing to repo.u-team.info because credentials are not set")
    }
}

fun versionFromGit(): Pair<String, Boolean> {
    Grgit.open(mapOf("currentDir" to project.rootDir)).use { git ->
        val headTag = git.tag
            .list()
            .find { it.commit.id == git.head().id }

        val clean = git.status().isClean || System.getenv("CI") != null
        if (!clean) {
            logger.lifecycle("Git state is dirty, version is a snapshot.")
        }

        return if (headTag != null && clean) headTag.name to true else "${git.head().id}-SNAPSHOT" to false
    }
}
