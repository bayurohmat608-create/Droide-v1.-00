pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    

    buildscript {
        repositories {


            maven {
                name = "R8Releases"
                url = uri("https://storage.googleapis.com/r8-releases/raw")
                content { includeModule("com.android.tools", "r8") }
            }
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // They must never be resolved from a remote repository with the same coordinates.

        exclusiveContent {
            forRepository {
                maven {
                    name = "DroideLocalCompat"
                    url = uri(rootDir.resolve("third_party/maven"))
                }
            }
            filter {
                includeGroup("com.baystudio.compat")
            }
        }

        // Custom compatibility groups are explicitly excluded here because the exclusive repository above owns them.


        maven {
            name = "DroideLocalSupport"
            url = uri(rootDir.resolve("third_party/maven"))
            content {
                excludeGroup("com.baystudio.compat")
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "Droide"
include(":app")
