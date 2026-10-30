package dev.omalauncher.pp.search

import dev.omalauncher.pp.widgets.lang.matchScore
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankerTest {
    private fun r(
        group: SearchGroup,
        title: String,
        query: String,
        score: Int = matchScore(title, query),
    ) = SearchResult(group, "search:$title", title, score, "open")

    private fun web(q: String) = SearchResult(SearchGroup.Web, "search:web", q, 0, "search")

    @Test fun intents() {
        assertEquals(QueryIntent.Install("btop"), SearchRanker.intent("install btop"))
        assertEquals(QueryIntent.Install("btop"), SearchRanker.intent("Install  btop "))
        assertEquals(QueryIntent.Install("btop"), SearchRanker.intent("instalar btop"))
        assertEquals(QueryIntent.Install("btop"), SearchRanker.intent("pacman -S btop"))
        assertEquals(QueryIntent.Web("owl language"), SearchRanker.intent("search the web for owl language"))
        assertEquals(QueryIntent.Web("owl"), SearchRanker.intent("search for owl"))
        assertEquals(QueryIntent.Web("owl"), SearchRanker.intent("google owl"))
        assertEquals(QueryIntent.Web("owl"), SearchRanker.intent("? owl"))
        assertEquals(QueryIntent.Web("owl"), SearchRanker.intent("buscar en la web owl"))
        assertEquals(QueryIntent.Web("owl"), SearchRanker.intent("pesquisar na web owl"))
        assertEquals(QueryIntent.Plain, SearchRanker.intent("install"))
        assertEquals(QueryIntent.Plain, SearchRanker.intent("installer"))
        assertEquals(QueryIntent.Plain, SearchRanker.intent("firefox"))
        assertEquals(QueryIntent.Plain, SearchRanker.intent("fix the failing test"))
    }

    @Test fun aCalculationLeads() {
        val q = "2^10"
        val calc = SearchResult(SearchGroup.Calc, "search:calc", "1024", Int.MAX_VALUE, "copy")
        val ranked = SearchRanker.rank(q, listOf(web(q), calc), appScore = 0)
        assertEquals(listOf("1024", q), ranked.results.map { it.title })
        assertEquals(1, ranked.lead)
    }

    @Test fun anAppThatMatchesKeepsEnter() {
        // "wifi" names an app (WiFi Analyzer, a prefix): the app opens, as the bar always did; the setting follows it.
        val q = "wifi"
        val ranked = SearchRanker.rank(q, listOf(r(SearchGroup.Settings, "wifi", q), web(q)), appScore = matchScore("WiFi Analyzer", q))
        assertEquals(0, ranked.lead)
        assertEquals(listOf("wifi", "wifi"), ranked.results.map { it.title })
        assertEquals(SearchGroup.Web, ranked.results.last().group)
    }

    @Test fun aSettingNamedTakesEnterWithoutAnApp() {
        val q = "bluetooth"
        val ranked = SearchRanker.rank(q, listOf(web(q), r(SearchGroup.Settings, "Bluetooth", q)), appScore = 0)
        assertEquals(1, ranked.lead)
        assertEquals("Bluetooth", ranked.results.first().title)
    }

    @Test fun aShortcutOrContactNamedTakesEnterWithoutAnApp() {
        val q = "new incognito"
        val ranked = SearchRanker.rank(q, listOf(r(SearchGroup.Shortcuts, "New incognito tab", q), web(q)), appScore = 0)
        assertEquals(1, ranked.lead)
        val c = "ana"
        assertEquals(1, SearchRanker.rank(c, listOf(r(SearchGroup.Contacts, "Ana Lima", c)), appScore = 0).lead)
    }

    @Test fun filesAndPackagesNeverTakeEnterFromTheAgent() {
        val q = "notes"
        val ranked = SearchRanker.rank(q, listOf(r(SearchGroup.Files, "notes.md", q), r(SearchGroup.Packages, "notes", q)), appScore = 0)
        assertEquals(0, ranked.lead)
    }

    @Test fun aSentenceGoesToTheAgent() {
        // Three words or more: never a name, so nothing takes Enter from the agent.
        val q = "open the bluetooth settings"
        val ranked = SearchRanker.rank(q, listOf(r(SearchGroup.Settings, "Bluetooth", q, score = 400)), appScore = 0)
        assertEquals(0, ranked.lead)
    }

    @Test fun weakMatchesDoNotLead() {
        // Letters in order, not a word's start: listed, but the agent keeps Enter.
        val q = "bt"
        val ranked = SearchRanker.rank(q, listOf(r(SearchGroup.Settings, "Bluetooth", q, score = 100)), appScore = 0)
        assertEquals(0, ranked.lead)
        assertEquals(1, ranked.results.size)
    }

    @Test fun installLeadsWithPackages() {
        val q = "install btop"
        val pkgs = listOf(r(SearchGroup.Packages, "btop-git", "btop"), r(SearchGroup.Packages, "btop", "btop"))
        val ranked = SearchRanker.rank(q, pkgs + web(q), appScore = 0)
        assertEquals(2, ranked.lead)
        assertEquals(listOf("btop", "btop-git", q), ranked.results.map { it.title })
    }

    @Test fun installStepsAsideForAnAppItNames() {
        val q = "install helper"
        val ranked = SearchRanker.rank(q, listOf(r(SearchGroup.Packages, "helper", "helper")), appScore = matchScore("Install Helper", q))
        assertEquals(0, ranked.lead)
    }

    @Test fun webSearchLeadsWhenAskedFor() {
        val q = "search the web for owl"
        val ranked = SearchRanker.rank(q, listOf(r(SearchGroup.Settings, "Owl", "owl"), web("owl")), appScore = 0)
        assertEquals(1, ranked.lead)
        assertEquals(SearchGroup.Web, ranked.results.first().group)
    }

    @Test fun groupsGoByTheirBestMatchAndKeepTogether() {
        val q = "cam"
        val results =
            listOf(
                r(SearchGroup.Files, "camping.txt", q),
                r(SearchGroup.Settings, "Camera settings", q),
                r(SearchGroup.Contacts, "Cam", q),
                r(SearchGroup.Files, "my-cam.jpg", q),
                web(q),
            )
        // An app (Camera, a prefix) keeps Enter: nothing leads.
        val ranked = SearchRanker.rank(q, results, appScore = matchScore("Camera", q))
        assertEquals(0, ranked.lead)
        // The contact is called exactly that; then settings and files, each group's best first; the web last.
        assertEquals(listOf("Cam", "Camera settings", "camping.txt", "my-cam.jpg", "cam"), ranked.results.map { it.title })
    }

    @Test fun eachGroupListsItsMost() {
        val q = "a"
        val many = (1..10).map { r(SearchGroup.Settings, "a$it", q) }
        assertEquals(SearchGroup.Settings.most, SearchRanker.rank(q, many, appScore = 0).results.size)
    }

    @Test fun nothingFoundIsEmpty() {
        val ranked = SearchRanker.rank("zzz", emptyList(), appScore = 0)
        assertEquals(0, ranked.lead)
        assertEquals(emptyList<SearchResult>(), ranked.results)
    }
}
