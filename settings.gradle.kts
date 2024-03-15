pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven {
            name = "GitHub Packages"
            setUrl("https://maven.pkg.github.com/FairyDevicesRD/thinklet.app.sdk")
            credentials {
                val properties = java.util.Properties()
                properties.load(file("github.properties").inputStream())
                username = properties.getProperty("username") ?: ""
                password = properties.getProperty("token") ?: ""
            }
        }
    }
    versionCatalogs {
        create("thinkletLibs") {
            from(files("gradle/thinklet.versions.toml"))
        }
    }
}

rootProject.name = "thinklet.squid.run"
include(":app")
