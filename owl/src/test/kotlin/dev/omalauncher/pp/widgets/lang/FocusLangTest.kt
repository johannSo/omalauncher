package dev.omalauncher.pp.widgets.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** android.usage, android.focus, focus(...) and settings("usage"): screen time and focus mode for widgets. */
class FocusLangTest {
    private class Host(
        program: Program,
    ) : dev.omalauncher.pp.widgets.lang.Host {
        val slots = Array<Value>(program.globals.size) { Null }
        val sent = ArrayList<String>()
        override val budget = Budget(10_000)

        override fun read(slot: Int): Value = slots[slot]

        override fun write(
            slot: Int,
            value: Value,
        ) {
            slots[slot] = value
        }

        override fun theme(name: String): Int? = null

        override fun time() = 0.0

        override fun act(
            name: String,
            args: List<Value>,
        ) {
            sent += "$name ${args.joinToString()}"
        }
    }

    private fun compileError(src: String): String =
        try {
            Compiler.compile(src)
            fail("expected a compile error")
            ""
        } catch (e: CompileError) {
            Source(src).format(e)
        }

    @Test fun usageAndFocusSources() {
        val p = Compiler.compile("widget {\n meta { owl: 17 }\n source u = android.usage\n source f = android.focus\n Text { text: \"${'$'}{u.total} ${'$'}{f.active}\" } }")
        assertEquals(listOf("android.usage", "android.focus"), p.globals.map { (it as Global.Source).kind })
        // Screen time is listed for the store's reviewer, as notifications are; it needs no Linux.
        assertEquals(listOf("read how long you used each app today"), p.commands)
        assertFalse(p.needsLinux)
        assertTrue(compileError("widget {\n meta { owl: 16 }\n source u = android.usage\n Text { text: 1 } }").contains("android.usage needs owl 17"))
        assertTrue(compileError("widget {\n meta { owl: 16 }\n source f = android.focus\n Text { text: 1 } }").contains("android.focus needs owl 17"))
    }

    @Test fun focusActionAndUsageSettings() {
        val p = Compiler.compile("widget {\n meta { owl: 17 }\n onTap: { focus(\"work\", 25); focus(\"off\"); settings(\"usage\") }\n Text { text: 1 } }")
        val host = Host(p)
        p.onTap!!.run(Env.root(host), emptyList())
        assertEquals(listOf("focus work, 25", "focus off", "settings usage"), host.sent)
        assertTrue(p.commands.any { it.startsWith("switch focus mode") })
        assertTrue(compileError("widget {\n meta { owl: 16 }\n Text { text: 1; onTap: focus(\"work\") } }").contains("focus needs owl 17"))
        assertTrue(compileError("widget {\n meta { owl: 16 }\n Text { text: 1; onTap: settings(\"usage\") } }").contains("needs owl 17"))
        assertTrue(compileError("widget {\n meta { owl: 17 }\n Text { text: 1; onTap: focus() } }").contains("focus takes 1-2 values"))
        // Input's focus property is another thing, and stays as it was.
        Compiler.compile("widget {\n meta { owl: 6 }\n screen { Input { focus: true } }\n Text { text: 1 } }")
    }
}
