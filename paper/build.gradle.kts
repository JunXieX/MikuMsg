plugins {
    java
}

dependencies {
    // Paper API 26.3（Java 25），适配 26.3 服务端
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

val pluginVersion = project.version.toString()

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("MikuMsg-Paper")
    // 产物直接输出到项目根目录
    destinationDirectory.set(rootDir)
}
