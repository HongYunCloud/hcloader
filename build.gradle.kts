plugins {
    id("java")
    id("java-library")
    id("maven-publish")
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = "io.github.hongyuncloud.hcloader"

    val baseVersion = "1.0"
    val buildNumber = System.getenv("GITHUB_RUN_NUMBER")
    if (buildNumber == null) {
        version = "$baseVersion-SNAPSHOT"
    } else {
        version = "$baseVersion-$buildNumber"
    }

    repositories {
        mavenCentral()
        maven("https://maven.lenni0451.net/releases") {
            content {
                includeGroup("net.raphimc.javadowngrader")
            }
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8

        withSourcesJar()
    }

    publishing {
        repositories {
            if (System.getenv("CI").toBoolean()) {
                maven("https://r.bgp.ink/maven/") {
                    credentials {
                        username = System.getenv("R_BGP_INK_USERNAME")
                        password = System.getenv("R_BGP_INK_PASSWORD")
                    }
                }
            } else {
                maven(rootProject.layout.buildDirectory.dir("maven"))
            }
        }

        publications {
            create<MavenPublication>("mavenJar") {
                if (project.path != ":") {
                    artifactId = rootProject.name + project.path.replace(':', '-')
                }

                from(components["java"])
            }
        }
    }

    dependencies {
        compileOnly("org.projectlombok:lombok:1.18.26")
        annotationProcessor("org.projectlombok:lombok:1.18.26")

        compileOnly("org.jetbrains:annotations:24.1.0")

        testImplementation(platform("org.junit:junit-bom:5.9.1"))
        testImplementation("org.junit.jupiter:junit-jupiter")
    }

    tasks.test {
        useJUnitPlatform()
    }
}