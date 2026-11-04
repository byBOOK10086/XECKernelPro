package com.ghostlock.app.domain.model

data class CpuPair(val primary: Int, val consumer: Int) {
    override fun toString(): String = "$primary,$consumer"
}

/** One editable advisory execution value (PROFILE-SUGGEST-01 / profile-ui). */
data class ExecutionFieldValue(
    val path: String,
    val value: Long,
    val overridden: Boolean,
)

/** One node of the resolved profile tree: a JSON group or a numeric leaf. */
data class ProfileFieldNode(
    val path: String,
    val name: String,
    val value: Long? = null,
    /** True when this field or any descendant has an explicit override. */
    val overridden: Boolean = false,
    val children: List<ProfileFieldNode> = emptyList(),
) {
    val isGroup: Boolean get() = children.isNotEmpty()
}

/** Resolved execution view for the advanced editor (controller-owned). */
data class ProfileConfig(
    val release: String,
    val hasProfile: Boolean,
    /** Hierarchical view of every numeric leaf, override flags included. */
    val roots: List<ProfileFieldNode> = emptyList(),
    /** General (execution tuning) subset exposed by the parameters screen. */
    val general: List<ExecutionFieldValue> = emptyList(),
    /** Explicit route from the profile; null means geometry inference. */
    val route: String? = null,
    /** Declared fallback route ("none"/"<route>"); null means unset. */
    val fallbackTo: String? = null,
    /** Dotted paths whose resolved value violates the geometry rules. */
    val invalidPaths: Set<String> = emptySet(),
) {
    companion object {
        /** Routes a profile may declare ("" is the inference fallback). */
        val Routes = listOf("tcp_zerocopy", "select_stack", "multicast_waiter")

        /**
         * Route-independent execution tuning paths the general editor always
         * exposes. Route-specific tuning is appended dynamically from the
         * resolved profile for the active route (plus its fallback), so a
         * profile never shows another route's knobs.
         */
        val GeneralPaths = listOf(
            "execution.selected_cpus.main",
            "execution.selected_cpus.consumer",
            "execution.heap.prepare_max_attempts",
            "execution.heap.prepare_timeout_ms",
            "execution.heap.kernelsnitch_timeout_ms",
            "execution.race.route_wait_ms",
            "execution.race.route_done_timeout_ms",
            "execution.race.setup_settle_us",
            "execution.race.state_poll_interval_us",
            "execution.stages.w1_attempts",
            "execution.stages.w1_settle_us",
            "execution.stages.w1_scratch_repair_attempts",
            "execution.stages.w2_attempts",
            "execution.stages.w2_settle_us",
            "execution.stages.w3_chain_rounds",
            "execution.stages.w3_attempts",
            "execution.stages.w3_settle_us",
            "execution.handoff.pre_dispatch_settle_ms",
            "execution.handoff.module_poll_attempts",
            "execution.handoff.module_poll_interval_ms",
            "execution.handoff.enforce_poll_attempts",
            "execution.handoff.enforce_poll_interval_ms",
        )
    }
}

sealed interface OffsetImportResult {
    data class Imported(val releases: List<String>) : OffsetImportResult
    data class RequiresOverwrite(val releases: List<String>) : OffsetImportResult
    data object AlreadyPresent : OffsetImportResult
    data class MissingIncludes(val files: List<String>) : OffsetImportResult
    data class Failed(val reason: String) : OffsetImportResult
}

sealed interface ParseResult {
    /** Fields only an xbl_config/uefi sidecar can supply (e.g.
     * `kernel_phys_load`) that the parsed profile is still missing. */
    data class Parsed(
        val releases: List<String>,
        val missing: Set<String> = emptySet(),
        /** Name of the stored document, so the caller can auto-load it. */
        val documentName: String? = null,
    ) : ParseResult

    data class RequiresOverwrite(
        val releases: List<String>,
        val missing: Set<String> = emptySet(),
    ) : ParseResult

    data object AlreadyPresent : ParseResult

    data class Failed(val code: Int, val reason: String? = null) : ParseResult
}
