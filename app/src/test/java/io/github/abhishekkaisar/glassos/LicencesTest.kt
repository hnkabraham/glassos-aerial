package io.github.abhishekkaisar.glassos

import org.junit.Assert.assertEquals
import org.junit.Test

class LicencesTest {
    @Test fun noticesFirstThenEveryFileByName() {
        val files = listOf("MIT-time64.txt", "GPL-3.0.txt", "THIRD_PARTY_NOTICES.md", "apache-x.txt", "LGPL-2.1.txt")
        assertEquals(listOf("THIRD_PARTY_NOTICES.md", "apache-x.txt", "GPL-3.0.txt", "LGPL-2.1.txt", "MIT-time64.txt"), Licences.order(files))
        assertEquals(emptyList<String>(), Licences.order(emptyList()))
    }

    @Test fun titles() {
        assertEquals("Third-party notices", Licences.title("THIRD_PARTY_NOTICES.md"))
        assertEquals("GNU General Public License v3", Licences.title("GPL-3.0.txt"))
        assertEquals("MIT licence (openssl-cmake)", Licences.title("MIT-openssl-cmake.txt"))
        // A file added later still shows, under its own name.
        assertEquals("BSD-3-Clause", Licences.title("BSD-3-Clause.txt"))
    }

    @Test fun markdownParagraphsAndListItemsReflow() {
        val md = "# Notices\n\nGlassOS Aerial is free\nsoftware under the GPL.\n\n- one item\n  continued\n- two\n\n" +
            "| A | B |\n|---|---|\n| x | y |\n\n```sh\ngit clone x\n  indented\n```\nAfter\ncode.\n"
        assertEquals("# Notices\n\nGlassOS Aerial is free software under the GPL.\n\n- one item continued\n- two\n\n" +
            "| A | B |\n|---|---|\n| x | y |\n\n```sh\ngit clone x\n  indented\n```\nAfter code.\n", Licences.reflow(md))
    }

    @Test fun plainTextKeepsHeadingsShortLinesAndIndentedText() {
        val gpl = "                    GNU GENERAL PUBLIC LICENSE\n                       Version 3, 29 June 2007\n\n" +
            "  Everyone is permitted\nto copy.\n    a) The work must carry prominent notices stating that you modified\n" +
            "    it, and giving a relevant date.\n"
        assertEquals(gpl, Licences.text("GPL-3.0.txt", gpl))
        // A header's lines broken on purpose, and a list item, stay apart.
        val jsmn = "/*\n * jsmn.c\n * Simple JSON parser\n *\n * Copyright (c) 2010 Serge A. Zaitsev\n" +
            " * Updated to use size_t for token offsets and harden against overflows.\n *   (Nikias Bassen, January 2026)\n */"
        assertEquals(jsmn, Licences.text("MIT-jsmn.txt", jsmn))
        assertEquals("1. one line that is long enough to have wrapped onto the next one\n2. two",
            Licences.reflowText("1. one line that is long enough to have wrapped onto the next one\n2. two"))
        assertEquals("a b", Licences.text("X.md", "a\r\nb"))
    }

    @Test fun plainTextParagraphsThatOnlyWrappedAreJoined() {
        // MIT's all-caps warranty paragraph, as most of the MIT files have it, then as a C comment (csrp, jsmn).
        val mit = "Copyright (c) 2015-2018 The ViaDuck Project\n\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n" +
            "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n" +
            "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.\n"
        assertEquals("Copyright (c) 2015-2018 The ViaDuck Project\n\nTHE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY " +
            "OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A " +
            "PARTICULAR PURPOSE AND NONINFRINGEMENT.\n", Licences.text("MIT-openssl-cmake.txt", mit))
        val comment = " *\n * THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n" +
            " * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n * FITNESS.\n */"
        assertEquals(" *\n * THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING " +
            "BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS.\n */", Licences.reflowText(comment))
        // The GPL's paragraphs start indented and go on from the left edge.
        val gpl = "  The GNU General Public License is a free, copyleft license for\nsoftware and other kinds of works.\n\n" +
            "  16. Limitation of Liability.\n"
        assertEquals("  The GNU General Public License is a free, copyleft license for software and other kinds of works.\n\n" +
            "  16. Limitation of Liability.\n", Licences.text("GPL-3.0.txt", gpl))
    }
}
