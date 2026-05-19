plugins {
    `java-library`
}

dependencies {
    implementation(project(":"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "--enable-preview",
        "-Xlint:-preview"
    ))
}

tasks.test {
    useJUnitPlatform()
    dependsOn(rootProject.tasks.named("generateNativeIndex"))
    classpath += files(rootProject.layout.buildDirectory.dir("generated-natives"))
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    testLogging {
        showStandardStreams = true
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
