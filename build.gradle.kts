import org.gradle.api.tasks.JavaExec
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

val jflex by configurations.creating
val javaCup by configurations.creating
val generatedSqlLexerSources =
    layout.buildDirectory.dir("generated/sources/sql/lexer")
val generatedSqlParserSources =
    layout.buildDirectory.dir("generated/sources/sql/parser")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("io.tempokv.bootstrap.TempoKvApplication")
}

dependencies {
    implementation("com.github.vbmacher:java-cup-runtime:11b-20160615")
    jflex("de.jflex:jflex:1.9.1")
    javaCup("com.github.vbmacher:java-cup:11b-20160615")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:lincheck:2.39")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.main {
    java.srcDir(generatedSqlLexerSources)
    java.srcDir(generatedSqlParserSources)
}

val jmh by sourceSets.creating {
    java.srcDir("src/jmh/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[jmh.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get())

dependencies {
    add(jmh.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(jmh.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

val generateSqlLexer = tasks.register<JavaExec>("generateSqlLexer") {
    description = "Generates the TempoKV SQL lexer from its JFlex specification."
    group = "build"
    classpath = jflex
    mainClass.set("jflex.Main")
    val specification = layout.projectDirectory.file(
        "src/main/jflex/io/tempokv/protocol/sql/TempoLexer.flex")
    inputs.file(specification)
    outputs.dir(generatedSqlLexerSources)
    doFirst {
        generatedSqlLexerSources.get().asFile.mkdirs()
    }
    args(
        "--quiet",
        "-d", generatedSqlLexerSources.get().asFile.absolutePath,
        specification.asFile.absolutePath)
}

val generateSqlParser = tasks.register<JavaExec>("generateSqlParser") {
    description = "Generates the TempoKV SQL parser from its Java CUP grammar."
    group = "build"
    classpath = javaCup
    mainClass.set("java_cup.Main")
    val specification = layout.projectDirectory.file(
        "src/main/cup/io/tempokv/protocol/sql/TempoParser.cup")
    inputs.file(specification)
    outputs.dir(generatedSqlParserSources)
    doFirst {
        generatedSqlParserSources.get().asFile.mkdirs()
    }
    args(
        "-destdir", generatedSqlParserSources.get().asFile.absolutePath,
        "-parser", "TempoParser",
        "-symbols", "SqlSymbols",
        specification.asFile.absolutePath)
}

tasks.compileJava {
    dependsOn(generateSqlLexer, generateSqlParser)
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

tasks.register<JavaExec>("jmh") {
    description = "Runs reproducible in-process JMH benchmarks."
    group = "benchmark"
    dependsOn(jmh.classesTaskName)
    classpath = jmh.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val resultFile = layout.buildDirectory.file("benchmarks/jmh-result.json")
    doFirst {
        resultFile.get().asFile.parentFile.mkdirs()
        val configured = providers.gradleProperty("jmhArgs").orNull
        args = if (configured.isNullOrBlank()) {
            listOf(
                "-wi", "2",
                "-i", "3",
                "-w", "500ms",
                "-r", "1s",
                "-f", "1",
                "-rf", "json",
                "-rff", resultFile.get().asFile.absolutePath)
        } else {
            configured.trim().split(Regex("\\s+"))
        }
    }
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
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
