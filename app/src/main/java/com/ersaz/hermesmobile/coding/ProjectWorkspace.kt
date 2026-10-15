package com.ersaz.hermesmobile.coding

import com.ersaz.hermesmobile.HermesRuntime
import java.io.File
import java.security.MessageDigest

fun projectRoot(rt: HermesRuntime): File {
    val base = rt.sandbox.hostPath("/root/projects").canonicalFile
    val root = rt.sandbox.hostPath(rt.settings.codingProject).canonicalFile
    require(root.isDirectory && (root == base || root.path.startsWith(base.path + File.separator))) { "Choose an existing project in /root/projects" }
    return root
}
fun checkpointStore(rt: HermesRuntime, root: File): WorkspaceCheckpoint {
    val hash = MessageDigest.getInstance("SHA-256").digest(root.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
    return WorkspaceCheckpoint(root, File(rt.hermesHome, "checkpoints/$hash"))
}
internal val projectMutationLock = Any()
fun trackedProjectWrite(rt: HermesRuntime, file: File, write: () -> Unit) = synchronized(projectMutationLock) {
    val root = runCatching { projectRoot(rt) }.getOrNull()
    val target = file.canonicalFile
    if (root == null || !target.path.startsWith(root.path + File.separator) || target.relativeTo(root).invariantSeparatorsPath.split('/').contains(".git")) {
        write()
    } else {
        val checkpoints = checkpointStore(rt, root)
        val id = checkpoints.capture(listOf(target.relativeTo(root).invariantSeparatorsPath))
        write()
        checkpoints.seal(id)
    }
}
fun specWorkflow(rt: HermesRuntime, feature: String = "001-feature"): SpecWorkflow {
    val names = mapOf(CodingPhase.CONSTITUTION to "constitution", CodingPhase.SPECIFY to "spec", CodingPhase.PLAN to "plan", CodingPhase.TASKS to "tasks")
    val templates = names.mapValues { (_, name) -> rt.app.assets.open("coding/spec-kit/$name-template.md").bufferedReader().use { it.readText() } }
    return SpecWorkflow(templates, feature)
}

fun projectTaskStore(rt: HermesRuntime, root: File): CodingTaskStore {
    val hash = MessageDigest.getInstance("SHA-256").digest(root.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
    return CodingTaskStore(root, File(rt.app.filesDir, "coding-evidence/$hash.json"))
}
