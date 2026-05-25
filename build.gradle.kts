plugins {
    `java-library`
}

group = "dev.runelite"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.runelite.net")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    compileOnly("net.runelite:runelite-api:latest.release")
    compileOnly("net.runelite:client:latest.release")
    compileOnly("org.pf4j:pf4j:3.6.0")
    compileOnly("com.google.inject:guice:4.1.0")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.pf4j:pf4j:3.6.0")
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
