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

/**
 * A `request_approval` call from an agent, or a tool's use of the vault item [vaultItem] (its name [vaultName]),
 * waiting for the user's answer.
 */
public data class ApprovalRequest(
    val id: String,
    val action: PermAction?,
    val details: String,
    val vaultItem: String? = null,
    val vaultName: String = "",
) {
    /** What Ren asks to do, as the sheet, the notification and the activity log name it. */
    val label: String get() = action?.label ?: "Use $vaultName"
}

/**
 * The user's answer; [remember] is "Always allow", which grants [ApprovalRequest.action], or lets the agents use
 * [ApprovalRequest.vaultItem] without asking, from now on.
 */
public data class ApprovalAnswer(val approved: Boolean, val remember: Boolean = false)
