package com.claudian.call

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextUtilsTest {
    private fun strip(s: String) = SpeechTextUtils.stripMarkdownForSpeech(s)
    private fun split(t: String, c: Int = 0) = SpeechTextUtils.extractCompletedSentences(t, c)

    // ---- sentence splitter ----
    @Test fun twoSentencesLastIsHeldBack() {
        val (out, cur) = split("Hello there. How are you? Fine")
        assertEquals(listOf("Hello there.", "How are you?"), out)
        // Cursor sits just before the space that precedes the held-back tail.
        assertEquals("Hello there. How are you?".length, cur)
    }

    @Test fun sentenceAtVeryEndIsNotEmittedYet() {
        // Streaming: the final chunk may still grow, so it waits for more text.
        val (out, cur) = split("Complete sentence.")
        assertEquals(emptyList<String>(), out)
        assertEquals(0, cur)
    }

    @Test fun cursorAtOrPastEndReturnsNothing() {
        assertEquals(emptyList<String>() to 5, split("Hello", 5))
        assertEquals(emptyList<String>() to 9, split("Hello", 9))
    }

    @Test fun emptyText() {
        assertEquals(emptyList<String>() to 0, split(""))
    }

    @Test fun cursorAdvancesIncrementally() {
        val t1 = "One. Two. Th"
        val (o1, c1) = split(t1)
        assertEquals(listOf("One.", "Two."), o1)
        val t2 = "One. Two. Three. Fo"
        val (o2, c2) = split(t2, c1)
        assertEquals(listOf("Three."), o2)
        assertEquals("One. Two. Three.".length, c2)
    }

    @Test fun newlineIsABoundary() {
        val (out, _) = split("First line\nSecond line\nthird")
        assertEquals(listOf("First line", "Second line"), out)
    }

    // KNOWN BUG — flips in M4: a decimal point mid-sentence makes the regex skip the text before
    // it, so "Version 3." is silently dropped from speech (expected "Version 3.14 is out.").
    @Test fun decimalNumberDropsTextBeforeIt() {
        val (out, _) = split("Version 3.14 is out. Next one")
        assertEquals(listOf("14 is out."), out)
    }

    @Test fun multiplePunctuationStaysTogether() {
        val (out, _) = split("Really?! Yes. ok")
        assertEquals(listOf("Really?!", "Yes."), out)
    }

    @Test fun abbreviationsDoSplit() {
        // Documents current behavior: "Dr." followed by a space is treated as a sentence end.
        val (out, _) = split("Dr. Smith arrived. Then more")
        assertEquals(listOf("Dr.", "Smith arrived."), out)
    }

    @Test fun noBoundaryYetEmitsNothing() {
        val (out, cur) = split("still talking without end")
        assertEquals(emptyList<String>(), out)
        assertEquals(0, cur)
    }

    // ---- markdown stripping ----
    @Test fun plainTextUnchanged() = assertEquals("Just words.", strip("Just words."))

    @Test fun codeBlockReplaced() =
        assertEquals("Before (see screen for code) after", strip("Before\n```kotlin\nval x = 1\n```\nafter"))

    @Test fun inlineCodeKeepsContent() = assertEquals("run ls now", strip("run `ls` now"))

    @Test fun linkKeepsLabel() = assertEquals("see docs here", strip("see [docs](https://x.io/a) here"))

    @Test fun imageKeepsAlt() = assertEquals("a cat", strip("![a cat](http://x/y.png)"))

    @Test fun bareUrlBecomesALink() = assertEquals("open a link now", strip("open https://example.com/path?q=1 now"))

    @Test fun filePathReducedToBasename() =
        assertEquals("edit Foo.kt please", strip("edit /home/user/app/src/Foo.kt please"))

    @Test fun headingsQuotesAndBulletsStripped() {
        assertEquals("Title quoted item one item two", strip("# Title\n> quoted\n- item one\n* item two"))
    }

    @Test fun boldItalicStrikeStripped() {
        assertEquals("bold italic strike both", strip("**bold** *italic* ~~strike~~ ***both***"))
    }

    @Test fun underscoreEmphasis() = assertEquals("under bold", strip("_under_ __bold__"))

    @Test fun tableRowsBecomeCommaLists() {
        assertEquals("Name, Age Bob, 3", strip("| Name | Age |\n|---|---|\n| Bob | 3 |"))
    }

    @Test fun strayMarkersRemoved() = assertEquals("a b", strip("a * b"))

    @Test fun whitespaceCollapsedAndTrimmed() = assertEquals("a b c", strip("  a \n\n b\t\tc  "))

    @Test fun emptyInput() = assertEquals("", strip(""))

    // Surprise-in-waiting: snake_case identifiers lose their underscores.
    @Test fun snakeCaseIdentifierLosesUnderscores() = assertEquals("my varname here", strip("my var_name here"))
}
