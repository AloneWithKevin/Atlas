plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

group = "nl.pixelretreat"
version = "0.1.7"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val folia = files("../Veyra-release/folia-api/build/libs/folia-api-26.3.local-SNAPSHOT.jar")
val veyra = files("../.build-deps/veyra-api-26.3.jar")
val campfire = files("../Campfire/build/libs/Campfire-0.5.0-api.jar")
val closet = files("../Closet/build/libs/Closet-0.1.4-api.jar")

dependencies {
    compileOnly(folia)
    compileOnly(veyra)
    compileOnly(campfire)
    compileOnly(closet)
    compileOnly("net.kyori:adventure-api:5.2.0")
    compileOnly("net.kyori:adventure-text-serializer-plain:5.2.0")
    compileOnly("com.google.guava:guava:33.3.1-jre")
    compileOnly(files("../.build-deps/bungeecord-chat.jar"))
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    implementation("com.zaxxer:HikariCP:7.0.2")
    testImplementation(folia)
    testImplementation(veyra)
    testImplementation(campfire)
    testImplementation(files("../Campfire/build/libs/Campfire-0.5.0.jar"))
    testImplementation(closet)
    testImplementation(files("../Closet/build/libs/Closet-0.1.4.jar"))
    testImplementation(files("../.build-deps/bungeecord-chat.jar"))
    testImplementation("net.kyori:adventure-api:5.2.0")
    testImplementation("net.kyori:adventure-text-minimessage:5.2.0")
    testImplementation("net.kyori:adventure-text-serializer-plain:5.2.0")
    testImplementation("com.google.guava:guava:33.3.1-jre")
    testImplementation("org.yaml:snakeyaml:2.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("org.apache.logging.log4j:log4j-api:2.26.0")
    testImplementation("net.kyori:adventure-text-logger-slf4j:5.2.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test {
    useJUnitPlatform()
    val scratch = layout.buildDirectory.dir("tmp/junit").get().asFile
    doFirst { scratch.mkdirs() }
    systemProperty("java.io.tmpdir", scratch.absolutePath)
}
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
tasks.jar { archiveClassifier.set("plain") }
tasks.register<Jar>("apiJar") {
    archiveClassifier.set("api")
    from(sourceSets.main.get().output) { include("nl/pixelretreat/atlas/api/**") }
}
tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.mariadb.jdbc", "nl.pixelretreat.atlas.internal.mariadb")
    relocate("com.zaxxer.hikari", "nl.pixelretreat.atlas.internal.hikari")
    dependencies { exclude(dependency("org.slf4j:slf4j-api:.*")) }
    mergeServiceFiles()
}
tasks.build { dependsOn(tasks.shadowJar) }
