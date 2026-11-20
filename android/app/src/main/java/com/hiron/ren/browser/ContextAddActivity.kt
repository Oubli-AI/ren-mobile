package com.hiron.ren.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.hiron.ren.core.ren

/**
 * "Add to Ren context" in Android's text selection menu (`ACTION_PROCESS_TEXT`), on Ren's pages and in every other
 * app: the selected text goes to Ren's context as a `selection`, as ren desktop's page context menu adds one. A
 * selection made in Ren's browser carries the page it came from.
 */
public class ContextAddActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        val runtime = ren
        when {
            text.isEmpty() -> Unit
            !runtime.contextWidget.account.value.contextReady ->
                Toast.makeText(this, "Sign in to your ren account and finish its interview to keep context.", Toast.LENGTH_LONG).show()
            else -> {
                val page = if (referrer?.host == packageName) runtime.browser.state.value.current?.takeUnless { it.home } else null
                runtime.contextWidget.addContext("selection", page?.title?.ifEmpty { null } ?: text.take(TITLE_LENGTH), page?.url.orEmpty(), text)
                Toast.makeText(this, "Adding to Ren context", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private companion object {
        const val TITLE_LENGTH = 80
    }
}
