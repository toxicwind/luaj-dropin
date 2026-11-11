plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.luaj:luaj-jse:3.0.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("src")
        // LuaJDropIn.kt is the Android host (Context, Handler, JSONObject) —
        // not compilable on the JVM. JavaBridge.kt is the pure-JVM core under
        // test; the Android host is compiled by the app's own Android build.
        kotlin.exclude("**/LuaJDropIn.kt")
    }
    test {
        kotlin.srcDir("test")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}