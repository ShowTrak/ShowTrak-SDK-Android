// Top-level build file. Plugin versions are declared here and applied in modules.
plugins {
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

allprojects {
    group = providers.gradleProperty("GROUP").orElse("io.github.showtrak").get()
    version = providers.gradleProperty("VERSION_NAME").orElse("1.0.2").get()
}
