package ru.forum.adbfastboottool

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationParityTest {
    @Test
    fun englishAndRussianStringKeysMatch() {
        val english = stringKeys(resourceFile("values/strings.xml"))
        val russian = stringKeys(resourceFile("values-ru/strings.xml"))

        assertTrue("English strings must not be empty", english.isNotEmpty())
        assertEquals("English and Russian string keys must match", english, russian)
    }

    private fun resourceFile(relativePath: String): File {
        val moduleRelative = File("src/main/res/$relativePath")
        if (moduleRelative.isFile) return moduleRelative

        val rootRelative = File("app/src/main/res/$relativePath")
        check(rootRelative.isFile) { "Missing resource file: $relativePath" }
        return rootRelative
    }

    private fun stringKeys(file: File): Set<String> {
        val namePattern = Regex("<string\\s+name=\"([^\"]+)\"")
        return namePattern.findAll(file.readText())
            .map { it.groupValues[1] }
            .toSortedSet()
    }
}
