package com.github.maxto3.pixelims

import com.topjohnwu.superuser.Shell
import java.io.IOException

object RootShell {

    private var rootAvailable: Boolean? = null

    /**
     * Check whether root (Magisk) access is available.
     * Result is cached; use [refreshRootStatus] to re-check.
     */
    fun isRootAvailable(): Boolean {
        if (rootAvailable == null) {
            rootAvailable = try {
                // Use libsu to get a root shell. If it succeeds, root is available.
                Shell.getShell().isRoot
            } catch (_: IOException) {
                false
            }
        }
        return rootAvailable == true
    }

    /**
     * Re-check root availability on next call to [isRootAvailable].
     */
    fun refreshRootStatus() {
        rootAvailable = null
    }

    /**
     * Execute a shell command as root and return the result.
     * @param command The shell command to execute.
     * @return [Shell.Result] from the execution.
     */
    fun exec(command: String): Shell.Result {
        return Shell.cmd(command).exec()
    }

    /**
     * Execute a list of shell commands as root and return the result.
     * @param commands List of shell commands.
     * @return [Shell.Result] from the execution.
     */
    fun exec(vararg commands: String): Shell.Result {
        require(commands.isNotEmpty()) { "At least one command is required" }
        val job = Shell.cmd(commands[0])
        for (i in 1 until commands.size) {
            job.add(commands[i])
        }
        return job.exec()
    }
}
