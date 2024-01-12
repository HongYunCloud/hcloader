plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.25.0")

    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
}

tasks.processResources {
    dependsOn(project(":").tasks.jar)
    dependsOn(project(":static-runtime").tasks.jar)

    with(copySpec {
        from(project(":").tasks.jar)
        rename { "ink/bgp/hcloader/gradle/hc-loader-runtime.jar" }
    })

    with(copySpec {
        from(project(":static-runtime").tasks.jar)
        rename { "ink/bgp/hcloader/gradle/hc-loader-static-runtime.jar" }
    })
}

gradlePlugin {
    plugins {
        create("hcloader-gradle") {
            id = "io.github.hongyuncloud.hcloader.gradle"
            implementationClass = "ink.bgp.hcloader.gradle.HcLoaderGradlePlugin"
        }
    }
}