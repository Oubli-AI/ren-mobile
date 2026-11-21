package com.hiron.ren.voice

public enum class AgentMode { Voice, Text }

/**
 * The conversation the app holds with Ren's agents, whichever provider runs them: [start] a voice or text
 * session, [sendText], [switchMode], [stop]. The screens use only these members.
 */
public interface Assistant {
    /** The mode of the session that is starting or running; null when there is none. */
    public val mode: AgentMode?

    public fun start(mode: AgentMode, firstMessage: String? = null)

    /** Sends [text] to the text agent, starting a text session when none is running; returns the message's id. */
    public fun sendText(text: String): String

    public fun switchMode(mode: AgentMode)

    public fun stop()

    public fun hasMicrophone(): Boolean
}

/**
 * Who runs Ren's agents; the screens, tools and approvals are the same either way. Every provider but ElevenLabs is a
 * text model under the Strands agents on the workspace VM ([StrandsAgent]), named there by [strands]; Claude Code and
 * Antigravity are their CLIs on that server, with their own tools.
 */
public enum class Provider(public val label: String, public val strands: String?) {
    ElevenLabs("ElevenLabs", null),
    ClaudeCode("Claude Code", "claude-code"),
    Antigravity("Antigravity", "antigravity"),
    Gemini("Gemini", "gemini"),
    Nim("NVIDIA NIM", "nim"),
    Mistral("Mistral", "mistral"),
    Telnyx("Telnyx", "telnyx"),
    Xai("xAI", "xai");

    /** Whether this provider runs as a CLI on the workspace server that signs in with the user's own account. */
    public val cliAuth: Boolean get() = this == ClaudeCode || this == Antigravity
}
