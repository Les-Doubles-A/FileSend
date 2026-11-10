plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Windows : un socket multicast IPv4 est refuse sur une pile double IPv4/IPv6.
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    // Test de memoire (AC-F2-6) : tas limite a 128 Mo, active par -PbigTests.
    if (project.hasProperty("bigTests")) {
        maxHeapSize = "128m"
        systemProperty("bigTests", "1")
        filter { includeTestsMatching("*HostSendTest*") }
    }
    // Vecteurs partages avec l'implementation Rust, et binaire Windows pour l'interoperabilite.
    systemProperty("protocolTests", rootProject.projectDir.parentFile.resolve("protocol-tests").absolutePath)
    systemProperty("windowsDir", rootProject.projectDir.parentFile.resolve("windows").absolutePath)
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
