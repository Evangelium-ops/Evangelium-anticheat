plugins {
    application
    java
}

dependencies {
    implementation(project(":chronos:core"))
    implementation(project(":chronos:protocol"))
    implementation("io.grpc:grpc-netty-shaded:1.68.1")
    implementation("io.grpc:grpc-stub:1.68.1")
    implementation("io.grpc:grpc-protobuf:1.68.1")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

application {
    mainClass.set("io.wildsmp.anticheat.backend.Main")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("anticheat-backend")
}

repositories { mavenCentral() }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.test { useJUnitPlatform() }
dependencies { testRuntimeOnly("org.junit.platform:junit-platform-launcher") }
