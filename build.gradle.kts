plugins {
    java
}

group = "dev.shareitem"
version = "26.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.2 build 121 = 26.2.build.121-stable (Java 25, Minecraft 26.2)
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    compileOnly("org.jetbrains:annotations:24.1.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
}
