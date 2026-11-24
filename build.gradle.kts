// The release bundle: `stage` publishes every published module, signed when the key is configured, into
// build/staging-repository/. The release workflow zips that directory and uploads it to the Maven
// Central Portal (RELEASING.md).

val publishedModules = listOf("core", "ktor", "stdio", "testing").map { ":kt-mcpx-$it" }

val cleanStaging = tasks.register<Delete>("cleanStaging") {
    description = "Deletes the staging repository, so a bundle never carries files from an earlier run."
    delete(layout.buildDirectory.dir("staging-repository"))
}

tasks.register("stage") {
    group = PublishingPlugin.PUBLISH_TASK_GROUP
    description = "Publishes every published module into build/staging-repository/ for the Central bundle."
    dependsOn(cleanStaging)
    dependsOn(publishedModules.map { "$it:publishMavenPublicationToStagingRepository" })
}
