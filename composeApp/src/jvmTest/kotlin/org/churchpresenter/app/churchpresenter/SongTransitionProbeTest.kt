package org.churchpresenter.app.churchpresenter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import org.churchpresenter.app.churchpresenter.presenter.SongPresenter
import org.churchpresenter.app.churchpresenter.viewmodel.PresenterManager
import org.churchpresenter.core.models.songs.LyricSection
import org.churchpresenter.settings.AppSettings
import org.churchpresenter.settings.SongLayoutExtras
import org.churchpresenter.settings.SongSettings
import org.churchpresenter.settings.utils.Constants
import org.churchpresenter.sharedui.models.Presenting
import kotlin.test.Test

/**
 * TEMPORARY PROBE -- drop this commit before merging. A diagnostic, not a regression test: it asserts
 * nothing. It drives the real PresenterManager + PresenterTransitionEffects + SongPresenter (and
 * BiblePresenter) frame by frame through slide changes under every option combination, and writes
 * what each frame drew -- every lyric node's text and height, and the frame's total ink -- to a report.
 *
 * Flags: RESIZE = a text changed size between frames while staying on screen; INK-DIP = the frame
 * went darker than both the slide before and after (a blank or double fade).
 *
 * Env: PROBE_OUT (report path; the Bible report lands beside it as bible-<name>), PROBE_PNG (dir to
 * write every frame as PNG), PROBE_ONLY (one combo, as printed in the report), PROBE_LIMIT (first N
 * combos). Run with `--rerun`, since env changes are not task inputs.
 */
// A throwaway diagnostic, dropped before merge -- not restyled to the gates.
@Suppress("NestedBlockDepth", "MaxLineLength", "LoopWithTooManyJumpStatements")
@OptIn(ExperimentalTestApi::class)
class SongTransitionProbeTest {

    private fun verse(song: String, n: Int, lines: Int, width: Int) = LyricSection(
        header = "[Verse $n]",
        type = Constants.SECTION_TYPE_VERSE,
        title = song,
        lines = List(lines) { i -> "$song v$n l$i " + "word ".repeat(width) },
    )

    private val songX = listOf(verse("X", 1, 8, 6), verse("X", 2, 2, 1), verse("X", 3, 4, 3))
    private val songY = listOf(verse("Y", 1, 2, 1), verse("Y", 2, 8, 6))

    data class Combo(
        val crossfade: Boolean,
        val eachSlide: Boolean,
        val lineMode: Boolean,
        val lookAhead: Boolean,
        val lowerThird: Boolean,
        val autoFit: Boolean,
    ) {
        override fun toString() = listOf(
            "xf" to crossfade, "each" to eachSlide, "line" to lineMode,
            "la" to lookAhead, "lt" to lowerThird, "fit" to autoFit,
        ).joinToString(" ") { (k, v) -> if (v) k.uppercase() else k }
    }

    private fun settings(c: Combo): AppSettings {
        val mode = if (c.lineMode) Constants.SONG_DISPLAY_MODE_LINE else Constants.SONG_DISPLAY_MODE_VERSE
        return AppSettings(
            songSettings = SongSettings(
                crossfade = c.crossfade,
                fadeIn = true,
                fadeOut = true,
                transitionDuration = 320f,
                lyricsFontSize = 150,
                lyricsFontSizeAutoFit = c.autoFit,
                lyricsLowerThirdFontSize = 150,
                lyricsLowerThirdFontSizeAutoFit = c.autoFit,
                lookAheadFontSize = 150,
                lookAheadFontSizeAutoFit = c.autoFit,
                fullscreenDisplayMode = mode,
                lowerThirdDisplayMode = mode,
                lookAheadDisplayMode = mode,
                lowerThirdLookAheadDisplayMode = mode,
                layoutExtras = SongLayoutExtras(
                    autoFitEachSlide = c.eachSlide,
                    autoFitEachSlideLowerThird = c.eachSlide,
                ),
            ),
        )
    }

    /** What SongsTab.sendToPresenter writes, in its order. */
    private fun PresenterManager.push(sections: List<LyricSection>, section: Int, line: Int, stamp: Int = 0) {
        setAllLyricSections(sections)
        setSongDisplaySectionIndex(section)
        setSongDisplayLineIndex(line)
        setLyricSection(sections[section].copy(bpm = stamp))
    }

    private data class Step(val name: String, val act: PresenterManager.() -> Unit)

    private fun scenarios(lineMode: Boolean): List<Pair<String, List<Step>>> {
        val last = songX[0].lines.lastIndex
        val base = listOf(
            "X1->X2 long->short" to listOf(Step("start") { push(songX, 0, if (lineMode) last else 0) },
                Step("go") { push(songX, 1, 0) }),
            "X2->X3 short->long" to listOf(Step("start") { push(songX, 1, 0) }, Step("go") { push(songX, 2, 0) }),
            "X1->Y1 song change" to listOf(Step("start") { push(songX, 0, if (lineMode) last else 0) },
                Step("go") { push(songY, 0, 0) }),
            "Y1->X1 song change short->long" to listOf(Step("start") { push(songY, 0, 0) },
                Step("go") { push(songX, 0, 0) }),
            "X1 re-push same (stamped)" to listOf(Step("start") { push(songX, 0, 0) },
                Step("go") { push(songX, 0, 0, stamp = 120) }),
            "rapid X1,X2,X3,X2" to listOf(Step("start") { push(songX, 0, 0) },
                Step("go") { push(songX, 1, 0) }, Step("+3f") { push(songX, 2, 0) },
                Step("+3f") { push(songX, 1, 0) }),
        )
        val line = if (lineMode) listOf(
            "X1 line 2->3" to listOf(Step("start") { push(songX, 0, 2) }, Step("go") { push(songX, 0, 3) }),
        ) else emptyList()
        return base + line
    }

    private var pngDir: File? = System.getenv("PROBE_PNG")?.let { File(it).apply { mkdirs() } }
    private var pngPrefix = ""

    private data class Frame(val t: Long, val texts: List<Pair<String, Float>>, val ink: Double)

    private fun ComposeUiTest.snap(t: Long): Frame {
        if (System.getenv("PROBE_IDLE") != null) waitForIdle()
        val img = onNodeWithTag("out").captureToImage()
        val nodes = onAllNodes(SemanticsMatcher("any text") { it.config.getOrNull(SemanticsProperties.Text) != null })
            .fetchSemanticsNodes()
        val texts = nodes.flatMap { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { a ->
                val raw = a.text.trim()
                val key = if ("Bv" in raw) raw.substring(raw.indexOf("Bv")).take(6) else raw.take(14)
                key to n.boundsInRoot.height
            }
        }.filter { (s, _) -> s.startsWith("X ") || s.startsWith("Y ") || s.startsWith("Bv") }.sortedBy { it.first }
        pngDir?.let { d -> javax.imageio.ImageIO.write(img.toAwtImage(), "png", File(d, "%s_%04d.png".format(pngPrefix, t + 16))) }
        val px = img.toPixelMap()
        var sum = 0.0
        for (y in 0 until px.height step 2) for (x in 0 until px.width step 2) {
            val c = px[x, y]
            sum += (c.red + c.green + c.blue) / 3.0
        }
        return Frame(t, texts, sum)
    }

    private fun runScenario(c: Combo, steps: List<Step>): List<Frame> {
        val frames = mutableListOf<Frame>()
        val appSettings = settings(c)
        runComposeUiTest {
            val manager = PresenterManager(showPresenterWindowInitially = false)
            setContent {
                PresenterTransitionEffects(manager, appSettings)
                val mode by manager.presentingMode
                val section by manager.displayedLyricSection
                val alpha by manager.songTransitionAlpha
                val lineIdx by manager.songDisplayLineIndex
                val all by manager.allLyricSections
                val secIdx by manager.songDisplaySectionIndex
                MaterialTheme {
                    Box(Modifier.size(640.dp, 360.dp).testTag("out")) {
                        if (mode == Presenting.LYRICS) {
                            SongPresenter(
                                lyricSection = section,
                                appSettings = appSettings,
                                isLowerThird = c.lowerThird,
                                transitionAlpha = alpha,
                                displayLineIndex = lineIdx,
                                lookAheadEnabled = c.lookAhead,
                                allLyricSections = all,
                                displaySectionIndex = secIdx,
                                crossfadeEnabled = c.crossfade,
                            )
                        }
                    }
                }
            }
            steps.first().act(manager)
            manager.setPresentingMode(Presenting.LYRICS)
            mainClock.advanceTimeBy(1500)
            waitForIdle()
            mainClock.autoAdvance = false
            frames += snap(-1)
            var t = 0L
            for (step in steps.drop(1)) {
                if (step.name == "+3f") repeat(3) { mainClock.advanceTimeByFrame(); t += 16; frames += snap(t) }
                step.act(manager)
            }
            repeat(50) { mainClock.advanceTimeByFrame(); t += 16; frames += snap(t) }
            mainClock.autoAdvance = true
        }
        return frames
    }

    /** Flags: per text, a size change while it stays on screen with the same node count; ink dips. */
    private fun analyse(frames: List<Frame>): List<String> {
        val flags = mutableListOf<String>()
        for (i in 1 until frames.size) {
            val prev = frames[i - 1].texts.groupBy({ it.first }, { it.second })
            val cur = frames[i].texts.groupBy({ it.first }, { it.second })
            for ((text, hs) in cur) {
                val ph = prev[text] ?: continue
                if (ph.size != hs.size) continue
                val a = ph.sorted(); val b = hs.sorted()
                if (a.zip(b).any { (x, y) -> kotlin.math.abs(x - y) > maxOf(x, y) * 0.03f }) {
                    flags += "RESIZE t=${frames[i].t} '$text' ${a.map { "%.1f".format(it) }} -> ${b.map { "%.1f".format(it) }}"
                }
            }
        }
        val inks = frames.drop(1).map { it.ink }
        val start = frames.first().ink; val end = inks.last()
        val lo = minOf(start, end)
        val minI = inks.indices.minBy { inks[it] }
        if (inks[minI] < lo * 0.85 && minI != inks.lastIndex) {
            flags += "INK-DIP t=${frames[minI + 1].t} min=%.0f start=%.0f end=%.0f".format(inks[minI], start, end)
        }
        // count separate dips: local minima below 0.9*lo
        var dips = 0; var inDip = false
        for (v in inks) { if (v < lo * 0.9) { if (!inDip) { dips++; inDip = true } } else inDip = false }
        if (dips > 1) flags += "MULTI-DIP x$dips"
        return flags
    }

    @Test
    fun probe() {
        val out = File(System.getenv("PROBE_OUT") ?: "build/song-transition-probe.txt").absoluteFile
        val report = StringBuilder()
        val combos = buildList {
            for (xf in listOf(false, true)) for (each in listOf(false, true)) for (line in listOf(false, true))
                for (la in listOf(false, true)) for (lt in listOf(false, true))
                    add(Combo(xf, each, line, la, lt, autoFit = true))
            add(Combo(true, false, false, false, false, autoFit = false))
            add(Combo(true, false, true, false, false, autoFit = false))
        }
        val limit = System.getenv("PROBE_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE
        val only = System.getenv("PROBE_ONLY")
        for (c in combos.filter { only == null || it.toString() == only }.take(limit)) for ((name, steps) in scenarios(c.lineMode)) {
            pngPrefix = (c.toString() + " " + name).replace(Regex("[^A-Za-z0-9]+"), "_")
            val frames = try { runScenario(c, steps) } catch (e: Throwable) {
                report.appendLine("[$c] $name: ERROR ${e::class.simpleName}: ${e.message?.take(200)}"); continue
            }
            val flags = analyse(frames)
            report.appendLine("[$c] $name: ${if (flags.isEmpty()) "ok" else flags.size.toString() + " flags"}")
            flags.take(8).forEach { report.appendLine("    $it") }
            if (flags.isNotEmpty()) {
                frames.take(14).forEach { f ->
                    report.appendLine("      t=${f.t} ink=%.0f ".format(f.ink) +
                        f.texts.joinToString { "${it.first}@%.1f".format(it.second) })
                }
            }
        }
        out.parentFile.mkdirs()
        out.writeText(report.toString())
    }

    private fun bv(n: Int, words: Int) = org.churchpresenter.core.models.bible.SelectedVerse(
        bookName = "John", chapter = 3, verseNumber = n, verseText = "Bv$n " + "word ".repeat(words),
    )

    private fun runBible(crossfade: Boolean, lowerThird: Boolean, steps: List<List<org.churchpresenter.core.models.bible.SelectedVerse>>): List<Frame> {
        val frames = mutableListOf<Frame>()
        val appSettings = AppSettings(
            bibleSettings = org.churchpresenter.settings.BibleSettings(crossfade = crossfade, transitionDuration = 320f),
        )
        runComposeUiTest {
            val manager = PresenterManager(showPresenterWindowInitially = false)
            setContent {
                PresenterTransitionEffects(manager, appSettings)
                val mode by manager.presentingMode
                val verses by manager.displayedVerses
                val alpha by manager.bibleTransitionAlpha
                MaterialTheme {
                    Box(Modifier.size(640.dp, 360.dp).testTag("out")) {
                        if (mode == Presenting.BIBLE) {
                            org.churchpresenter.app.churchpresenter.presenter.BiblePresenter(
                                selectedVerses = verses,
                                appSettings = appSettings,
                                isLowerThird = lowerThird,
                                transitionAlpha = alpha,
                                crossfadeEnabled = crossfade,
                            )
                        }
                    }
                }
            }
            manager.setSelectedVerses(steps.first())
            manager.setPresentingMode(Presenting.BIBLE)
            mainClock.advanceTimeBy(1500)
            waitForIdle()
            mainClock.autoAdvance = false
            frames += snap(-1)
            var t = 0L
            for ((i, v) in steps.drop(1).withIndex()) {
                if (i > 0) repeat(3) { mainClock.advanceTimeByFrame(); t += 16; frames += snap(t) }
                manager.setSelectedVerses(v)
            }
            repeat(50) { mainClock.advanceTimeByFrame(); t += 16; frames += snap(t) }
            mainClock.autoAdvance = true
        }
        return frames
    }

    @Test
    fun bibleProbe() {
        val out = File(System.getenv("PROBE_OUT") ?: "build/song-transition-probe.txt").absoluteFile
        val report = StringBuilder()
        val scenarios = listOf(
            "long->short" to listOf(listOf(bv(1, 60)), listOf(bv(2, 4))),
            "short->long" to listOf(listOf(bv(2, 4)), listOf(bv(3, 60))),
            "rapid" to listOf(listOf(bv(1, 60)), listOf(bv(2, 4)), listOf(bv(3, 30)), listOf(bv(4, 10))),
        )
        for (xf in listOf(false, true)) for (lt in listOf(false, true)) for ((name, steps) in scenarios) {
            val tag = "[bible xf=$xf lt=$lt] $name"
            pngPrefix = tag.replace(Regex("[^A-Za-z0-9]+"), "_")
            val frames = try { runBible(xf, lt, steps) } catch (e: Throwable) {
                report.appendLine("$tag: ERROR ${e::class.simpleName}: ${e.message?.take(200)}"); continue
            }
            val flags = analyse(frames)
            report.appendLine("$tag: ${if (flags.isEmpty()) "ok" else flags.size.toString() + " flags"}")
            flags.take(8).forEach { report.appendLine("    $it") }
            if (flags.isNotEmpty()) frames.take(10).forEach { f ->
                report.appendLine("      t=${f.t} ink=%.0f ".format(f.ink) + f.texts.joinToString { "${it.first}@%.1f".format(it.second) })
            }
        }
        out.parentFile.mkdirs()
        File(out.parentFile, "bible-" + out.name).writeText(report.toString())
    }
}
