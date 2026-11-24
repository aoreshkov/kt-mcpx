// Published modules: explicit API mode plus a committed ABI dump, so every change to the public
// surface is deliberate and shows up in review, and a signed Maven publication with sources and KDoc.
//
// Publishing (RELEASING.md): `publishToMavenLocal` for a local try-out, and the root's `stage` task for
// the bundle that goes to the Maven Central Portal. Signing is required only when the signing key is
// set, so local publications need no keys.

plugins {
    id("kt-mcpx-jvm")
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
}

kotlin {
    explicitApi()

    // Built into the Kotlin Gradle plugin; the DSL is still experimental.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        referenceDumpDir.set(layout.projectDirectory.dir("api"))
    }
}

java {
    withSourcesJar()
}

val repositoryUrl = "https://github.com/aoreshkov/kt-mcpx"

// Source links point at the release tag, so the KDoc matches the released source; snapshots link to main.
val sourceRef = version.toString().let { if (it.endsWith("-SNAPSHOT")) "main" else "v$it" }
dokka {
    moduleName = project.name
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory = layout.projectDirectory.dir("src/main/kotlin")
            remoteUrl("$repositoryUrl/blob/$sourceRef/${projectDir.name}/src/main/kotlin")
            remoteLineSuffix = "#L"
        }
    }
}

// Maven Central requires a javadoc artifact; for Kotlin that's the rendered KDoc.
val javadocJar = tasks.register<Jar>("javadocJar") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Packages the rendered KDoc as the -javadoc artifact."
    archiveClassifier = "javadoc"
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

val licenseFile = rootProject.layout.projectDirectory.file("LICENSE")
tasks.withType<Jar>().configureEach {
    into("META-INF") {
        from(licenseFile)
    }
}

// A stable JPMS module name, rather than one derived from the jar's file name: the module's root package.
val automaticModuleName = "app.oreshkov.ktmcpx" +
    project.name.removePrefix("kt-mcpx-").let { if (it == "core") "" else ".$it" }
tasks.named<Jar>("jar") {
    manifest {
        attributes("Automatic-Module-Name" to automaticModuleName)
    }
}

// Where `stage` collects every published module: the directory the release workflow zips into the
// Portal bundle.
val stagingRepository = rootProject.layout.buildDirectory.dir("staging-repository")

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)
            pom {
                name.set(project.name)
                description.set(project.provider { project.description ?: project.name })
                url.set(repositoryUrl)
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("aoreshkov")
                        name.set("Atanas Oreshkov")
                        url.set("https://github.com/aoreshkov")
                    }
                }
                scm {
                    url.set(repositoryUrl)
                    connection.set("scm:git:$repositoryUrl.git")
                    developerConnection.set("scm:git:ssh://git@github.com/aoreshkov/kt-mcpx.git")
                }
                issueManagement {
                    system.set("GitHub Issues")
                    url.set("$repositoryUrl/issues")
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(stagingRepository)
        }
    }
}

// Stale files from an earlier run must never reach a bundle: the root's cleanStaging runs first.
tasks.named("publishMavenPublicationToStagingRepository") {
    mustRunAfter(":cleanStaging")
}

// Signs only when the key is configured, so local publishing and CI builds need no keys. Signing is
// then required whenever this module's staging publish is going to run.
signing {
    val signingKey = providers.gradleProperty("signingInMemoryKey")
    val stagingTask = "${project.path}:publishMavenPublicationToStagingRepository"
    setRequired { signingKey.isPresent && gradle.taskGraph.hasTask(stagingTask) }
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), providers.gradleProperty("signingInMemoryKeyPassword").orElse("").get())
    }
    sign(publishing.publications)
}
