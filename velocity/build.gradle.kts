plugins {
    java
}

dependencies {
    // Velocity API 4.2.0（与 MikuAuth 3.6.0 所用版本一致）
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0")
    // MikuAuth 对外 API（cn.miku.auth.api.*）：仅编译期引用，运行时由 MikuAuth 插件
    // 的类加载器提供同名类——绝不可内嵌进本 jar，否则 Velocity 优先加载自身副本，
    // 两个 Class 对象状态不互通，MikuAuthProvider.get() 永远为空。
    // 依赖文件：velocity/libs/MikuAuth-3.6.0.jar（构建前由 CI 下载，见 .github/workflows/build.yml）
    //   来源：https://github.com/JunXieX/MikuAuth/releases/download/v3.6.0/MikuAuth-3.6.0.jar
    //   SHA-256：1c2aad9609adc609ba21d527fecf6d3137a2f366beadc1a7d04dfecd2cf4c667
    compileOnly(files("libs/MikuAuth-3.6.0.jar"))
    // MikuVanish 对外 API（dev.junxiex.mikuvanish.api.*）：同样 compileOnly，运行时
    // 类由 MikuVanish 代理端插件的类加载器提供。
    // 依赖文件：velocity/libs/MikuVanish-API-1.3.0.jar（构建前由 CI 下载，见 .github/workflows/build.yml）
    //   来源：https://github.com/JunXieX/MikuVanish/releases/download/v1.3.0/MikuVanish-API-1.3.0.jar
    //   SHA-256：43feabb819825a06b67131c66504927aa070e96ba1698470e6a870c7a6dbcf86
    compileOnly(files("libs/MikuVanish-API-1.3.0.jar"))
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

tasks.named<Jar>("jar") {
    archiveBaseName.set("MikuMsg-Velocity")
    // 产物直接输出到项目根目录
    destinationDirectory.set(rootDir)
}

// 版本号一致性校验：@Plugin 的 version 是编译期常量，无法由 Gradle 注入，
// 故在构建时比对它与 project.version，防止两处漂移（不一致直接构建失败）。
val verifyPluginVersion = tasks.register("verifyPluginVersion") {
    val sourceFile = layout.projectDirectory.file(
        "src/main/java/me/junxiex/mikumsg/velocity/MikuMsgVelocity.java"
    )
    val expected = project.version.toString()
    doLast {
        val actual = Regex("""version\s*=\s*"([^"]+)"""")
            .find(sourceFile.asFile.readText())
            ?.groupValues?.get(1)
        if (actual != expected) {
            throw GradleException(
                "MikuMsgVelocity 的 @Plugin version=\"$actual\" 与 Gradle version=\"$expected\" 不一致，请同步后再构建。"
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPluginVersion)
}
