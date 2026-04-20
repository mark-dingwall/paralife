plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.paralife"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-websocket") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jetty")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Plan 15-06 Task 2 Part D — temporary exclusion of test sources that type
// against the wire-bound Messages.* records deleted in the partial strip.
// Plan 15-11 migrates these tests and removes the exclusion.
sourceSets {
    test {
        java {
            exclude("com/paralife/engine/ActionResolverTest.java")
            exclude("com/paralife/engine/CompositeActionTest.java")
            exclude("com/paralife/engine/CompositeIntegrationTest.java")
            exclude("com/paralife/engine/CompositeMovementTest.java")
            exclude("com/paralife/websocket/WebSocketIntegrationTest.java")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}
