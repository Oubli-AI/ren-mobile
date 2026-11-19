package com.hiron.ren.device

/** A phone tool's answer: [data] is what the agent gets back, or why it failed. */
public data class ToolResult(val ok: Boolean, val data: Any?) {
    public companion object {
        public fun ok(data: Any?): ToolResult = ToolResult(true, data)
        public fun error(message: String): ToolResult = ToolResult(false, message)
    }
}
