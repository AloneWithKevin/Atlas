plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

group = "nl.pixelretreat"
version = "0.1.10"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val folia = files("../Veyra-release/folia-api/build/libs/folia-api-26.3.local-SNAPSHOT.jar")
val veyra = files("../.build-deps/veyra-api-item-exchange.jar")
val campfire = files(providers.gradleProperty("campfireApiJar").getOrElse("../.build-deps/campfire-api-current.jar"))
val closet = files(providers.gradleProperty("closetApiJar").getOrElse("../.build-deps/closet-api-pack-owner.jar"))
val postbox = files(providers.gradleProperty("postboxApiJar").getOrElse("../Postbox/build/libs/Postbox-0.1.0-api.jar"))
val logbookClient = files("../Logbook/client/build/libs/client-0.1.0.jar")
val logbookCore = files("../Logbook/core/build/libs/core-0.1.0.jar")
val logbookApi = files("../Logbook/api/build/libs/api-0.1.0.jar")

dependencies {
    compileOnly(folia)
    compileOnly(veyra)
    compileOnly(campfire)
    compileOnly(closet)
    compileOnly(postbox)
    compileOnly("net.kyori:adventure-api:5.2.0")
    compileOnly("net.kyori:adventure-text-serializer-plain:5.2.0")
    compileOnly("com.google.guava:guava:33.3.1-jre")
    compileOnly(files("../.build-deps/bungeecord-chat.jar"))
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation(logbookClient)
    testImplementation(logbookCore)
    testImplementation(logbookApi)
    testImplementation(folia)
    testImplementation(veyra)
    testImplementation(campfire)
    testImplementation(files(providers.gradleProperty("campfireFixtureJar").getOrElse("../.build-deps/campfire-test-current.jar")))
    testImplementation(closet)
    testImplementation(postbox)
    testImplementation(files(providers.gradleProperty("closetFixtureJar").getOrElse("../.build-deps/closet-test-pack-owner.jar")))
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
    systemProperty("closetTestSource", providers.gradleProperty("closetTestSource")
            .getOrElse(file("../Closet/content/legacy").absolutePath))
}
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
tasks.jar { archiveClassifier.set("plain") }
tasks.register<Jar>("apiJar") {
    archiveClassifier.set("api")
    from(sourceSets.main.get().output) { include("nl/pixelretreat/atlas/api/**") }
}
tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("Atlas.jar")
    relocate("org.mariadb.jdbc", "nl.pixelretreat.atlas.internal.mariadb")
    relocate("com.zaxxer.hikari", "nl.pixelretreat.atlas.internal.hikari")
    relocate("nl.pixelretreat.logbook.client", "nl.pixelretreat.atlas.internal.logbook.client")
    dependencies { exclude(dependency("org.slf4j:slf4j-api:.*")) }
    mergeServiceFiles()
}
tasks.build { dependsOn(tasks.shadowJar) }

// Closet is the sole resource-pack owner; consumers ship definitions only.
tasks.processResources { exclude("**/assets/**", "**/*.png", "**/*.ogg", "**/*.zip") }
