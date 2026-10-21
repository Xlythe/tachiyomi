plugins { kotlin("jvm") version "2.2.21" }

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    sourceSets {
        main {
            kotlin.srcDir("../../app/src/main/java")
            kotlin.include("eu/kanade/tachiyomi/ui/reader/filter/ReaderImageAnalysis.kt")
        }
        test {
            kotlin.srcDir("../../app/src/test/java")
            kotlin.include("eu/kanade/tachiyomi/ui/reader/filter/ReaderImageAnalysisTest.kt")
        }
    }
}
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.1")
}
tasks.test { useJUnitPlatform() }
