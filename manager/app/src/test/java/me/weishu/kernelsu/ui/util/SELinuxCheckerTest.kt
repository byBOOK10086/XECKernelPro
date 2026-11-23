package me.weishu.kernelsu.ui.util

import com.topjohnwu.superuser.Shell
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SELinuxCheckerTest {
    @Test fun repeatedQueriesKeepTheSharedShellUsable() {
        val shell = FakeShell(stdout = listOf("Enforcing"))
        repeat(2) { assertEquals("Enforcing", readSELinuxStatus(shell)) }
        assertFalse(shell.closed)
        assertEquals(listOf("getenforce", "getenforce"), shell.commands)
    }

    @Test fun nonRootShellDoesNotLaunchAProbe() {
        val shell = FakeShell(root = false, stdout = listOf("Permissive"))
        assertEquals("Unknown", readSELinuxStatus(shell))
        assertEquals(emptyList<String>(), shell.commands)
        assertFalse(shell.closed)
    }

    @Test fun failedProbeNeverClaimsEnforcing() {
        val shell = FakeShell(
            stdout = listOf("Enforcing"),
            stderr = listOf("getenforce: Permission denied"),
            exitCode = 1,
        )
        assertEquals("Unknown", readSELinuxStatus(shell))
        assertFalse(shell.closed)
    }

    @Test fun onlyExactSuccessfulStatesAreAccepted() {
        listOf("Enforcing", "Permissive", "Disabled").forEach { state ->
            assertEquals(state, readSELinuxStatus(FakeShell(stdout = listOf(" $state "))))
        }
        listOf(emptyList(), listOf("Unknown"), listOf("Enforcing", "Permissive"), listOf("Permission denied")).forEach { output ->
            assertEquals("Unknown", readSELinuxStatus(FakeShell(stdout = output)))
        }
    }

    private class FakeShell(
        private val root: Boolean = true,
        private val stdout: List<String> = emptyList(),
        private val stderr: List<String> = emptyList(),
        private val exitCode: Int = 0,
    ) : Shell() {
        var closed = false
        val commands = ArrayList<String>()
        override fun isAlive() = !closed
        override fun getStatus() = if (root) ROOT_SHELL else NON_ROOT_SHELL
        override fun close() { closed = true }
        override fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean {
            close()
            return true
        }
        override fun execTask(task: Task) = error("Unexpected task")
        override fun submitTask(task: Task) = error("Unexpected task")
        override fun newJob(): Job {
            check(!closed) { "Shared shell was closed" }
            return object : Job() {
                private var out: MutableList<String>? = null
                private var err: MutableList<String>? = null
                override fun to(stdout: MutableList<String>?): Job = apply { out = stdout }
                override fun to(stdout: MutableList<String>?, stderr: MutableList<String>?): Job = apply {
                    out = stdout
                    err = stderr
                }
                override fun add(vararg cmds: String): Job = apply { commands.addAll(cmds) }
                override fun add(input: InputStream): Job = error("Unexpected input")
                override fun exec(): Result {
                    out?.addAll(stdout)
                    err?.addAll(stderr)
                    return object : Result() {
                        override fun getOut() = stdout
                        override fun getErr() = stderr
                        override fun getCode() = exitCode
                    }
                }
                override fun submit(executor: Executor?, callback: ResultCallback?) = error("Unexpected submit")
                override fun enqueue(): Future<Result> = CompletableFuture.completedFuture(exec())
            }
        }
    }
}
