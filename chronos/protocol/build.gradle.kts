plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    implementation("io.grpc:grpc-protobuf:1.68.1")
    implementation("io.grpc:grpc-stub:1.68.1")
    implementation("com.google.protobuf:protobuf-java:4.28.2")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53") // @Generated on Java 9+ for grpc-stub

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.2"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.68.1"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("anticheat-protocol")
}

repositories { mavenCentral() }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.test { useJUnitPlatform() }

sourceSets.main { java.srcDir(rootProject.file("../launcher/license-shared/src/main/java")) }
