plugins { `java-library` }
repositories { maven("https://repo.grim.ac/snapshots"); maven("https://maven.grim.ac/public/snapshots"); maven("https://maven.grim.ac/public/releases"); mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(project(":chronos:core"))
    implementation(project(":chronos:protocol"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(libs.packetevents.spigot)
    implementation("io.grpc:grpc-netty-shaded:1.68.1")
    implementation("io.grpc:grpc-stub:1.68.1")
    implementation("io.grpc:grpc-protobuf:1.68.1")
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(libs.packetevents.spigot)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
// The single Bukkit descriptor belongs to AC, and its primary config belongs to Grim.
tasks.processResources { exclude("plugin.yml") }
