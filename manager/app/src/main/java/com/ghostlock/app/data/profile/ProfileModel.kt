package com.ghostlock.app.data.profile

import com.ghostlock.app.data.CredTemplate
import com.ghostlock.app.data.KernelOffsetTable
import com.ghostlock.app.data.TaskStructOffsets
import com.ghostlock.app.data.route.RouteKind

/** A sparse layer of execution values, keyed by their path under `execution`. */
data class SparseExecutionValues(val values: Map<String, ULong>)

/** Typed, path-carrying configuration error. */
data class ConfigError(val fieldPath: String, val message: String) {
    override fun toString(): String = "$fieldPath: $message"
}
