plugins {
    kotlin("jvm") version "2.4.21"
}

group = "pl.DropDbLikeItsHard"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.kdriver:core:0.6.3")
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Source: https://mvnrepository.com/artifact/tools.jackson.core/jackson-databind
    implementation("tools.jackson.core:jackson-databind:3.3.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.21")}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}