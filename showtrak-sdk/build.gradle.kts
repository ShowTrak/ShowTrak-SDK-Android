plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "io.showtrak.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Socket.IO transport (brings engine.io-client + OkHttp). Exposed as `api`
    // so consumers don't need to add it explicitly.
    api("io.socket:socket.io-client:2.1.0")
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        project.providers.gradleProperty("GROUP").orElse("io.github.showtrak").get(),
        project.providers.gradleProperty("POM_ARTIFACT_ID").orElse(project.name).get(),
        project.version.toString()
    )

    pom {
        name.set(project.providers.gradleProperty("POM_NAME").orElse("ShowTrak Android SDK"))
        description.set(
            project.providers.gradleProperty("POM_DESCRIPTION")
                .orElse("Reusable Android SDK for ShowTrak integrated clients.")
        )
        url.set(project.providers.gradleProperty("POM_URL").orElse("https://github.com/ShowTrak/ShowTrak-SDK-Android"))
        inceptionYear.set("2026")

        licenses {
            license {
                name.set(project.providers.gradleProperty("POM_LICENSE_NAME").orElse("MIT License"))
                url.set(project.providers.gradleProperty("POM_LICENSE_URL").orElse("https://opensource.org/licenses/MIT"))
            }
        }

        developers {
            developer {
                id.set(project.providers.gradleProperty("POM_DEVELOPER_ID").orElse("showtrak"))
                name.set(project.providers.gradleProperty("POM_DEVELOPER_NAME").orElse("ShowTrak"))
            }
        }

        scm {
            url.set(project.providers.gradleProperty("POM_SCM_URL").orElse("https://github.com/ShowTrak/ShowTrak-SDK-Android"))
            connection.set(
                project.providers.gradleProperty("POM_SCM_CONNECTION")
                    .orElse("scm:git:git://github.com/ShowTrak/ShowTrak-SDK-Android.git")
            )
            developerConnection.set(
                project.providers.gradleProperty("POM_SCM_DEV_CONNECTION")
                    .orElse("scm:git:ssh://git@github.com/ShowTrak/ShowTrak-SDK-Android.git")
            )
        }
    }
}
