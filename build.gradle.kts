plugins {
    java
}

allprojects {
    // 版本号唯一来源（Gradle 侧）：Velocity 的 @Plugin 注解为编译期常量，
    // 由 velocity 模块的 verifyPluginVersion 任务校验两处一致。
    group = "me.junxiex"
    version = "1.5.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

// 根项目只作聚合，不产出空 jar
tasks.named<Jar>("jar") {
    enabled = false
}
