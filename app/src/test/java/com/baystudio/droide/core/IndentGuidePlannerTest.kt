package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndentGuidePlannerTest {
    private val spaces4 = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test")

    @Test fun nestedSpaceIndentProducesContinuousGuides() {
        val guides = IndentGuidePlanner.plan(
            listOf(
                "fun main() {",
                "    if (ready) {",
                "        work()",
                "    }",
                "}",
            ),
            spaces4,
        )
        assertTrue(guides.contains(IndentGuideSegment(8, 1, 3, false)))
        assertTrue(guides.contains(IndentGuideSegment(4, 0, 4, false)))
    }

    @Test fun tabsUseVisualTabStopsAndBlankLinesDoNotBreakGuides() {
        val tabs = spaces4.copy(indentStyle = IndentStyle.TABS)
        val guides = IndentGuidePlanner.plan(
            listOf("root", "\tchild", "", "\t\tgrandchild", "\tchild2", "root2"),
            tabs,
        )
        assertTrue(guides.contains(IndentGuideSegment(8, 1, 4, false)))
        assertTrue(guides.contains(IndentGuideSegment(4, 0, 5, false)))
    }

    @Test fun eofGuideExtendsThroughLastIndentedLine() {
        val guides = IndentGuidePlanner.plan(listOf("root", "    child", "    child2"), spaces4)
        assertEquals(listOf(IndentGuideSegment(4, 0, 2, true)), guides)
    }

    @Test fun whitespaceOnlyDocumentDoesNotInventBlocks() {
        assertTrue(IndentGuidePlanner.plan(listOf("", "    ", "\t"), spaces4).isEmpty())
    }
}
