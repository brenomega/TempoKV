import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    java
    application
    jacoco
}

group = "io.tempokv"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("io.tempokv.bootstrap.TempoKvApplication")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val integrationTest by sourceSets.creating {
    java.srcDir("src/integrationTest/java")
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests for documented use cases."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    dependsOn(tasks.jar)
    systemProperty("tempokv.jar", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
}

tasks.check {
    dependsOn(integrationTestTask)
    dependsOn("jacocoAllReport")
}

tasks.register<JacocoReport>("jacocoAllReport") {
    description = "Generates combined unit and integration coverage."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.test, integrationTestTask)
    executionData(fileTree(layout.buildDirectory.dir("jacoco")) {
        include("*.exec")
    })
    sourceSets(sourceSets.main.get())
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
