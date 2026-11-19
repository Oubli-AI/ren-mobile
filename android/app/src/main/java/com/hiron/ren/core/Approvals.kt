package com.hiron.ren.core

/**
 * What the agents ask permission for: the `action_type` of the `request_approval` client tool, and the `perm_*`
 * dynamic variable each session starts with ("granted" or "prompt"). [label] is how the agents' prompts name it.
 */
public enum class PermAction(public val wire: String, public val label: String) {
    UseApps("use_apps", "Use apps"),
    SendMessages("send_messages", "Send messages"),
    ReadMessages("read_messages", "Read messages"),
    Purchases("purchases", "Purchases"),
    InstallApps("install_apps", "Install apps"),
    CloudCoding("cloud_coding", "Cloud coding"),
    Deploy("deploy", "Deploy"),
    CreateMusic("create_music", "Create music"),
    ;

    /** The dynamic variable the agents' prompts read, e.g. `perm_send_messages`. */
    public val variable: String get() = "perm_$wire"

    public companion object {
        public fun of(wire: String?): PermAction? = entries.firstOrNull { it.wire == wire }
    }
}

/** A `request_approval` call from an agent, waiting for the user's answer. */
public data class ApprovalRequest(val id: String, val action: PermAction, val details: String)

/** The user's answer; [remember] is "Always allow", which grants [ApprovalRequest.action] from now on. */
public data class ApprovalAnswer(val approved: Boolean, val remember: Boolean = false)
