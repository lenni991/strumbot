import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.github.johnrengelman.shadow") version "5.1.0"
    id("org.jetbrains.kotlin.jvm") version "1.3.61"
    application
}

application {
    mainClassName = "strumbot.Main"
}

group = "dev.minn"
version = "0.1.1"

repositories {
    jcenter()
    maven("https://oss.jfrog.org/artifactory/libs-release")
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("net.dv8tion:JDA:4.0.0_72")
    implementation("club.minnced:jda-reactor:1.0.0")
    implementation("club.minnced:discord-webhooks:0.1.8")
    implementation(kotlin("stdlib-jdk8"))
}

val build by tasks
val compileKotlin: KotlinCompile by tasks
val shadowJar: ShadowJar by tasks

compileKotlin.apply {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.create<Copy>("install") {
    dependsOn(shadowJar)
    enabled = File("config.json").let { it.exists() && it.canRead() }
    from(shadowJar.archiveFile.get())
    from("config.json")
    from("src/scripts")
    val output = "$buildDir/install"
    into("$output/")
    doFirst {
        File("$output/strumbot.jar").delete()
    }
    doLast {
        setupScript("$output/run.bat")
        setupScript("$output/run.sh")
        val archive = File("$output/${shadowJar.archiveFileName.get()}")
        archive.renameTo(File("$output/strumbot.jar"))
    }
}

build.dependsOn(shadowJar)

fun setupScript(path: String) {
    val file = File(path)
    file.writeText(file.readText()
        .replace("%NAME%", "strumbot.jar")
        .replace("%VERSION%", version.toString()))
    file.setExecutable(true)
    file.setReadable(true)
}