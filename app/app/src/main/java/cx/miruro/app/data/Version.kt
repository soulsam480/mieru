package cx.miruro.app.data

/** Dotted-numeric comparison: "1.10.0" is newer than "1.9.3". */
internal fun isNewerVersion(candidate: String, installed: String): Boolean {
    val remote = candidate.removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
    val local = installed.removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
    if (remote.isEmpty()) return false

    for (index in 0 until maxOf(remote.size, local.size)) {
        val r = remote.getOrElse(index) { 0 }
        val l = local.getOrElse(index) { 0 }
        if (r != l) return r > l
    }
    return false
}
