package app.nestbox.engine

import android.system.Os
import java.io.File

/** Reads /proc for the guest's processes (all run as this app's uid, so they are visible here). */
object ProcUtil {
    private val pageSize: Long by lazy { runCatching { Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrDefault(4096L) }

    /** pid → parent pid for every process this app can see. */
    fun parents(): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(d, "stat").readText() }.getOrNull() ?: return@forEach
            // "pid (comm) state ppid …" — comm may contain spaces and parentheses.
            val rest = stat.substringAfterLast(')').trim().split(' ')
            rest.getOrNull(1)?.toIntOrNull()?.let { out[pid] = it }
        }
        return out
    }

    fun descendants(root: Int, parents: Map<Int, Int> = parents()): List<Int> {
        val children = parents.entries.groupBy({ it.value }, { it.key })
        val out = ArrayList<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            children[p]?.forEach { c ->
                out += c
                queue += c
            }
        }
        return out
    }

    /** Resident memory of [pids] in bytes. Overcounts: every guest process maps the same libraries. */
    fun rssBytes(pids: List<Int>): Long = pids.sumOf { pid ->
        runCatching { File("/proc/$pid/statm").readText().trim().split(' ')[1].toLong() * pageSize }.getOrDefault(0L)
    }

    /**
     * Real memory use of [pids] in bytes: PSS splits each shared page between the processes that
     * map it, so the sum is what the machine costs the phone. Summing RSS instead counted the
     * guest's shared libraries and boot image once per process (≈10 GB for one Android 13 guest).
     */
    fun pssBytes(pids: List<Int>): Long = pids.sumOf { pid ->
        runCatching {
            File("/proc/$pid/smaps_rollup").useLines { lines ->
                lines.firstOrNull { it.startsWith("Pss:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.times(1024)
            } ?: 0L
        }.getOrDefault(0L)
    }

    fun kill(pids: Collection<Int>) {
        pids.forEach { runCatching { android.os.Process.killProcess(it) } }
    }

    /** Processes left behind by an earlier run (parent is init) whose working directory is under [dir]. */
    fun orphansUnder(dir: File): List<Int> {
        val prefix = dir.canonicalPath
        return parents().filter { (pid, ppid) ->
            ppid == 1 && runCatching { File("/proc/$pid/cwd").canonicalPath.startsWith(prefix) }.getOrDefault(false)
        }.keys.toList()
    }
}
