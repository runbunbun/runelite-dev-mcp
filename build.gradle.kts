plugins {
    `java-library`
}

group = "dev.runelite"
version = "0.1.6"

repositories {
    mavenCentral()
    maven("https://repo.runelite.net")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// RuneLite is pinned to a known-good release rather than `latest.release` so CI and
// contributor builds are reproducible. Bump deliberately when upstream cuts a release
// with API changes you've validated against.
val runeliteVersion = "1.12.26.3"

dependencies {
    compileOnly("net.runelite:runelite-api:$runeliteVersion")
    compileOnly("net.runelite:client:$runeliteVersion")
    compileOnly("org.pf4j:pf4j:3.6.0")
    compileOnly("com.google.inject:guice:4.1.0")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.pf4j:pf4j:3.6.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("net.runelite:runelite-api:$runeliteVersion")
    testRuntimeOnly("net.runelite:client:$runeliteVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "RuneLite Dev MCP",
            "Implementation-Version" to project.version
        )
    }
}
