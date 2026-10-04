plugins {
    kotlin("jvm")
    jacoco
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test { useJUnitPlatform(); finalizedBy(tasks.jacocoTestReport) }
tasks.jacocoTestReport { dependsOn(tasks.test); reports { xml.required.set(true); html.required.set(true) } }
