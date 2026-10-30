package dev.omalauncher.pp.widgets.lang

import dev.omalauncher.pp.widgets.market.Review
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** intent(...), broadcast(...), broadcast sources and `external`: widgets talking to other Android apps. */
class IntentsTest {
    /** States start at their first value; what's sent and reported is recorded. */
    private class Host(
        val program: Program,
    ) : dev.omalauncher.pp.widgets.lang.Host {
        val slots = Array<Value>(program.globals.size) { Null }
        val sent = ArrayList<String>()
        val reports = ArrayList<String>()
        override val budget = Budget(10_000)

        init {
            program.globals.forEachIndexed { i, g -> if (g is Global.State) slots[i] = g.init(Env.root(this)) }
        }

        override fun read(slot: Int): Value = (program.globals[slot] as? Global.Let)?.value?.invoke(Env.root(this)) ?: slots[slot]

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

        override fun intent(spec: IntentSpec) {
            sent += spec.toString()
        }

        override fun report(message: String) {
            reports += message
        }
    }

    private val w = "widget {\n meta { owl: 17 }\n"

    private fun compileError(src: String): String =
        try {
            Compiler.compile(src)
            fail("expected a compile error")
            ""
        } catch (e: CompileError) {
            Source(src).format(e)
        }

    /** Compiles a widget whose tile tap is [h], taps it, and gives what it sent and reported. */
    private fun tap(
        h: String,
        decls: String = "",
    ): Pair<List<String>, List<String>> {
        val p = Compiler.compile(w + decls + "\n onTap: $h\n Text { text: 1 } }")
        val host = Host(p)
        p.onTap!!.run(Env.root(host), emptyList())
        return host.sent to host.reports
    }

    @Test fun intentStartsAnActivityWithTypedExtras() {
        val (sent, reports) =
            tap(
                "intent(\"android.intent.action.SEND\", {\"android.intent.extra.TEXT\": msg, \"level:float\": 3, n: 2, big: 3000000000, f: 1.5, on: true, tags: [\"a\", 1]}, " +
                    "package: \"org.example.app\", type: \"text/plain\", category: [\"android.intent.category.DEFAULT\"])",
                " state msg = \"hi\"",
            )
        assertEquals(emptyList<String>(), reports)
        assertEquals(
            listOf(
                "intent android.intent.action.SEND package org.example.app type text/plain categories android.intent.category.DEFAULT " +
                    "extras android.intent.extra.TEXT:string=hi, level:float=3.0, n:int=2, big:long=3000000000, f:float=1.5, on:bool=true, tags:strings=[a,1]",
            ),
            sent,
        )
        // Only data: a link in whatever app opens it; a component alone opens that activity.
        assertEquals(listOf("intent data geo:0,0?q=coffee"), tap("intent(data: \"geo:0,0?q=coffee\")").first)
        assertEquals(listOf("intent component org.x/.Main"), tap("intent(null, {}, component: \"org.x/.Main\")").first)
    }

    @Test fun broadcastGoesToAnAppOrToEveryApp() {
        val (sent, _) = tap("{ broadcast(\"net.dinglisch.android.tasker.ACTION_TASK\", {task_name: \"lights\"}, package: \"net.dinglisch.android.taskerm\"); broadcast(\"org.x.PING\") }")
        assertEquals(
            listOf("broadcast net.dinglisch.android.tasker.ACTION_TASK package net.dinglisch.android.taskerm extras task_name:string=lights", "broadcast org.x.PING"),
            sent,
        )
        val p = Compiler.compile(w + " state q = \"a\"\n onTap: { broadcast(\"org.x.PING\"); broadcast(\"org.x.SET\", {a: 1, b: 2}, package: \"org.x\"); intent(\"android.intent.action.VIEW\", null, data: \"https://x.test/${'$'}{enc(q)}\") }\n Text { text: 1 } }")
        assertEquals(
            listOf(
                "send broadcast org.x.PING to any app",
                "send broadcast org.x.SET to org.x with extras a, b",
                "start activity android.intent.action.VIEW with data https://x.test/${Compiler.VALUE}",
            ),
            p.commands,
        )
        assertFalse(p.needsLinux)
        // The reviewer is told when every app hears one, and when a value says where it goes.
        val warnings = Review.risks(p.commands)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("every app on the phone can hear"))
        val built = Compiler.compile(w + " state pkg = \"org.x\"\n onTap: intent(\"org.x.OPEN\", {}, package: pkg)\n Text { text: 1 } }").commands
        assertEquals(listOf("start activity org.x.OPEN to ${Compiler.VALUE}"), built)
        assertTrue(Review.risks(built).single().contains("a widget value names"))
    }

    @Test fun valuesThatArentThereYetSendNothing() {
        // Loading: an action from a source with no value yet.
        val p = Compiler.compile(w + " source s = http(\"https://x.test/\")\n onTap: broadcast(\"org.x.A\", {v: 1}, package: s)\n Text { text: 1 } }")
        val host = Host(p)
        p.onTap!!.run(Env.root(host), emptyList())
        assertEquals(emptyList<String>(), host.sent)
        host.slots[0] = Str("org.y")
        p.onTap!!.run(Env.root(host), emptyList())
        assertEquals(listOf("broadcast org.x.A package org.y extras v:int=1"), host.sent)
        // A value that isn't a package, or names the launcher, is refused and reported.
        host.sent.clear()
        host.slots[0] = Str("dev.omalauncher.pp")
        p.onTap!!.run(Env.root(host), emptyList())
        assertEquals(emptyList<String>(), host.sent)
        assertTrue(host.reports.single(), host.reports.single().contains("is the launcher itself"))
    }

    @Test fun extrasThatDontFitTheirTypeAreReported() {
        val (sent, reports) = tap("broadcast(\"org.x.A\", {\"n:int\": \"abc\"})")
        assertEquals(emptyList<String>(), sent)
        assertEquals(listOf("broadcast: extra n:int takes a whole number, not \"abc\""), reports)
        assertTrue(tap("broadcast(\"org.x.A\", [1])").second.single().contains("extras are a map"))
    }

    @Test fun intentsAreCheckedAsTheyCompile() {
        fun error(h: String) = compileError(w + " onTap: $h\n Text { text: 1 } }")
        assertTrue(error("intent()").contains("needs an action, a package, a component or data"))
        assertTrue(error("broadcast(null, {}, package: \"org.x\")").contains("broadcast(...) takes an action"))
        assertTrue(error("intent(\"not an action\")").contains("isn't a dotted name"))
        assertTrue(error("intent(\"org.x.A\", {}, package: \"dev.omalauncher.pp\")").contains("is the launcher itself"))
        assertTrue(error("intent(\"org.x.A\", {}, component: \"dev.omalauncher.pp/.MainActivity\")").contains("the launcher's own"))
        assertTrue(error("broadcast(\"dev.omalauncher.pp.action.SET\", {}, package: \"org.x\")").contains("the launcher's own"))
        assertTrue(error("intent(\"org.x.A\", {}, component: \"org.x\")").contains("isn't a package and a class"))
        assertTrue(error("broadcast(\"org.x.A\", {}, data: \"x\")").contains("broadcast takes package: and component:"))
        assertTrue(error("intent(\"org.x.A\", {}, flags: 1)").contains("intent takes package:"))
        assertTrue(error("intent(\"a.B\", {}, \"c\")").contains("takes an action and a map of extras"))
        assertTrue(compileError(w + " Text { text: intent(\"a.B\") } }").contains("is an action"))
        // A secret never goes into an intent.
        compileError(w + " state tok = null secret\n onTap: broadcast(\"org.x.A\", {t: tok})\n Text { text: 1 } }").let { assertTrue(it, it.contains("secret")) }
        assertTrue(compileError("widget {\n meta { owl: 16 }\n onTap: broadcast(\"org.x.A\")\n Text { text: 1 } }").contains("broadcast needs owl 17"))
    }

    @Test fun broadcastSourcesHearOtherApps() {
        val p =
            Compiler.compile(
                w + " state level = 0\n source t = broadcast([\"net.dinglisch.android.tasker.BATTERY\", \"org.x.B\"])\n" +
                    " on t each { level = value.extras.level }\n Text { text: \"${'$'}{t.action} ${'$'}{level}\" } }",
            )
        val t = p.globals[1] as Global.Source
        assertEquals("broadcast", t.kind)
        assertEquals(Lst(listOf(Str("net.dinglisch.android.tasker.BATTERY"), Str("org.x.B"))), t.arg!!.const)
        assertEquals(1, p.triggers.single().each)
        assertEquals(listOf("hear broadcasts any app sends: net.dinglisch.android.tasker.BATTERY", "hear broadcasts any app sends: org.x.B"), p.commands)
        assertFalse(p.needsLinux)
        val host = Host(p)
        val value = Intents.received("org.x.B", null, mapOf("level" to 42, "who" to "tasker"))
        host.slots[1] = value
        p.triggers.single().action.run(Env.root(host), listOf(value, Null))
        assertEquals(Num(42.0), host.slots[0])

        assertTrue(compileError(w + " state a = \"org.x.A\"\n source t = broadcast(a)\n Text { text: t } }").contains("must be constant"))
        assertTrue(compileError(w + " source t = broadcast(\"dev.omalauncher.pp.action.SET\")\n Text { text: t } }").contains("the launcher's own actions"))
        assertTrue(compileError(w + " source t = broadcast(\"org.x.A\") every 5s\n Text { text: t } }").contains("takes no every"))
        assertTrue(compileError(w + " source t = broadcast(\"a b\")\n Text { text: t } }").contains("isn't an intent action"))
        assertTrue(compileError(w + " source t = broadcast(\"org.x.A\")\n Text { text: 1; onTap: send(t, 1) } }").contains("send's first value is a ws(...) source"))
        assertTrue(compileError("widget {\n meta { owl: 16 }\n source t = broadcast(\"org.x.A\")\n Text { text: t } }").contains("needs owl 17"))
    }

    @Test fun externalTakesWhatOtherAppsSet() {
        val p = Compiler.compile(w + " source ext = external\n on ext.temp { }\n Text { text: ext.temp ?? \"…\" } }")
        assertEquals("external", (p.globals[0] as Global.Source).kind)
        assertEquals(listOf("take values other apps set, once you allow it in settings"), p.commands)
        assertTrue(compileError(w + " source ext = external every 5s\n Text { text: ext } }").contains("takes no every"))

        var values = Intents.setOutside(emptyMap(), "temp", "21.5").getOrThrow()
        assertEquals(mapOf("temp" to Num(21.5)), values)
        values = Intents.setOutside(values, "state", "on the way").getOrThrow()
        assertEquals(Str("on the way"), values["state"])
        values = Intents.setOutside(values, "list", "[1, 2]").getOrThrow()
        assertEquals(Lst(listOf(Num(1.0), Num(2.0))), values["list"])
        assertEquals(TRUE, Intents.setOutside(values, "on", true).getOrThrow()["on"])
        // No value removes the key.
        assertEquals(setOf("state", "list"), Intents.setOutside(values, "temp", null).getOrThrow().keys)
        assertTrue(Intents.setOutside(values, "a b", "1").isFailure)
        assertTrue(Intents.setOutside(values, null, "1").isFailure)
        assertTrue(Intents.setOutside(values, "big", "x".repeat(Intents.MAX_OUTSIDE_TEXT + 1)).isFailure)
        val full = (1..Intents.MAX_OUTSIDE_KEYS).associate { "k$it" to Num(1.0) as Value }
        assertTrue(Intents.setOutside(full, "more", "1").isFailure)
        assertTrue(Intents.setOutside(full, "k1", "2").isSuccess)
    }

    @Test fun extrasAndReceivedValues() {
        val extras = Intents.extras(Obj(mapOf("a:stringList" to Lst(listOf(Str("x"))), "b:double" to Str("2.5"), "c:bool" to Str("false"), "m" to Obj(mapOf("d" to Num(1.0))), "gone" to Null)))
        assertEquals("a:stringList=[x], b:double=2.5, c:bool=false, m:bundle=[d:int=1]", extras.joinToString())
        // A key with a colon that isn't a type keeps it.
        assertEquals("time:12:string=x", Intents.extras(Obj(mapOf("time:12" to Str("x")))).single().toString())
        assertNull(Intents.check(IntentSpec(false, null, "org.x", null, null, null, emptyList(), emptyList())))
        assertEquals(
            Obj(mapOf("action" to Str("a.B"), "data" to Null, "extras" to Obj(mapOf("n" to Lst(listOf(Num(1.0), Num(2.0))), "b" to Obj(mapOf("s" to Str("x"))))))),
            Intents.received("a.B", null, mapOf("n" to intArrayOf(1, 2), "b" to mapOf("s" to "x"), "none" to null)),
        )
    }
}
