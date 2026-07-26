package com.jonnyzzz.mcpSteroid.koltinc

import kotlin.io.path.exists
import kotlin.io.path.writeText
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder


class KotlinBuildsSessionTest {
    @JvmField
    @Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun smokeCompile() {
        val smokeDir = tempFolder.newFolder("smoke").toPath()
        val outputJar = smokeDir.resolve("out.jar")
        val source = smokeDir.resolve("source.kt")
        source.writeText("""
        fun main() { println("Hello") }
        """.trimIndent())

        KotlinBuildsSession(tempFolder.newFolder("bta-temp").toPath()).use {
            it.compileKotlin(
                sources = listOf(source),
                destinationDir = outputJar,
                executionPolicy = KotlinBuildsSession.CompilationExecutionPolicy.IN_PROCESS,
            ) {
                set(JvmCompilerArguments.CLASSPATH, listOf(it.defaultStdlibJar))
            }

            assertTrue(outputJar.exists())
        }
    }
}
