plugins {
    kotlin("jvm") version "2.2.21"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
application {
    mainClass.set("dev.maduikit.codegen.MainKt")
}
val smokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("bash", "scripts/smoke.sh", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
val figmaSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("bash", "scripts/figma-smoke.sh", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
val gradientSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("bash", "scripts/gradient-smoke.sh", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(smokeTest, figmaSmokeTest, gradientSmokeTest) }
val instanceSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("bash", "scripts/instance-smoke.sh", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(instanceSmokeTest) }
val roundedSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("bash", "scripts/rounded-smoke.sh", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(roundedSmokeTest) }
val distributionSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("bash", "scripts/distribution-smoke.sh", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(distributionSmokeTest) }
val resourceSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("bash", "scripts/resource-smoke.sh", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(resourceSmokeTest) }
val compoundResourceSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("bash", "scripts/compound-resource-smoke.sh", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(compoundResourceSmokeTest) }
val wrappedResourceSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("bash", "scripts/wrapped-resource-smoke.sh", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(wrappedResourceSmokeTest) }

val verifiedGenerationSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("python3", "scripts/verified-generation-smoke.py", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(verifiedGenerationSmokeTest) }

val atomicFrameSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("python3", "scripts/atomic-frame-smoke.py", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(atomicFrameSmokeTest) }

val mediumWeightSmokeTest by tasks.registering(Exec::class) {
    dependsOn(tasks.installDist)
    commandLine("python3", "scripts/medium-weight-smoke.py", layout.buildDirectory.file("install/MadUIKitCodegen/bin/MadUIKitCodegen").get().asFile.absolutePath)
}
tasks.check { dependsOn(mediumWeightSmokeTest) }
