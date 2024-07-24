package io.nativeblocks.compiler.util

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.isInternal
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.NonExistLocation
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * This File is copied from:
 * @see <a href="https://github.com/raamcosta/compose-destinations">Compose Destinations</a>
 */
internal object DefaultParameterValueReader {

    fun readDefaultValue(
        resolver: (pckg: String, name: String) -> ResolvedSymbol?,
        lineText: String,
        packageName: String,
        imports: List<String>,
        argName: String,
        argType: String,
    ): DefaultValue {
        var auxText = lineText

        val anchors = arrayOf(
            argName,
            ":",
            argType,
            "=",
        )

        var index: Int
        anchors.forEach {
            index = auxText.indexOf(it)
            auxText = auxText.removeRange(0, index)
        }
        auxText = auxText.removeRange(0, 1)

        index = auxText.indexOfFirst { it != ' ' }
        auxText = auxText.removeRange(0, index)

        return if (auxText.startsWith("\"")) {
            DefaultValue(stringLiteralValue(auxText))
        } else {
            importedDefaultValue(resolver, auxText, packageName, imports)
        }
    }

    private fun stringLiteralValue(auxText: String): String {
        var finalText = auxText
        val splits = finalText.split("\"")
        finalText = splits[1]

        var i = 2
        while (finalText.endsWith('\\')) {
            finalText += "\"${splits[i]}"
            i++
        }

        return "\"$finalText\""
    }

    private fun importedDefaultValue(
        resolver: (pckg: String, name: String) -> ResolvedSymbol?,
        auxText: String,
        packageName: String,
        imports: List<String>,
    ): DefaultValue {
        var result = auxText
        if (!result.firstParenthesisIsClosing() && result.contains("(")) {
            result = result.defaultValueCodeWithFunctionCalls()
        } else {
            val index = result.indexOfFirst { it == ' ' || it == ',' || it == '\n' || it == ')' }

            if (index != -1) {
                result = result.removeRange(index, result.length)
            }
        }

        if (result == "true" ||
            result == "false" ||
            result == "null" ||
            result.first().isDigit()
        ) {
            return DefaultValue(result)
        }

        val importableAux = result.removeFromTo("(", ")")

        if (result.length - importableAux.length > 2) {
            // we detected a function call with args, we can't resolve this
            throw IllegalArgumentException(
                "NativeblocksIntegration arguments using function calls with parameters as their default value " +
                        "are not currently supported (near: '$auxText')",
            )
        }

        val importable = importableAux.split(".")[0]
        val defValueImports = imports.filter { it.endsWith(".$importable") }

        if (defValueImports.isNotEmpty()) {
            return DefaultValue(result, defValueImports)
        }

        if (resolver.invoke(packageName, importable).existsAndIsAccessible()) {
            return DefaultValue(result, listOf("$packageName.$importable"))
        }

        val wholePackageImports = imports
            .filter { it.endsWith(".*") }

        val validImports = wholePackageImports
            .filter { resolver.invoke(it.removeSuffix(".*"), importable).existsAndIsAccessible() }

        if (validImports.size == 1) {
            return DefaultValue(result, listOf(validImports[0]))
        }

        if (result.startsWith("arrayListOf(") || // std kotlin lib
            result.startsWith("arrayOf(") // std kotlin lib
        ) {
            return DefaultValue(result)
        }

        if (resolver.invoke(packageName, importable).existsAndIsPrivate()) {
            throw IllegalArgumentException(
                "NativeblocksIntegration arguments with default values which uses " +
                        "a private declaration are not currently supported (near: '$auxText')",
            )
        }
        return DefaultValue(result, wholePackageImports)
    }
}

private fun String.firstParenthesisIsClosing(): Boolean {
    val indexOfFirstOpening = this.indexOfFirst { it == '(' }
    val indexOfFirstClosing = this.indexOfFirst { it == ')' }

    return indexOfFirstClosing < indexOfFirstOpening
}

private fun String.defaultValueCodeWithFunctionCalls(): String {
    var idx = 0

    while (true) {
        val indexOfOpen = this.indexOf('(', idx)
        if (indexOfOpen == -1) break

        idx = this.indexOf(')', indexOfOpen)

        if (idx == -1) {
            throw IllegalArgumentException(
                "NativeblocksIntegration arguments with multiline function call as their default value" +
                        "are not currently supported (near: '$this')",
            )
        }
    }

    if (idx < this.lastIndex) {
        idx++
        val textToConsider = this.removeRange(0, idx)
        val indexFinish =
            textToConsider.indexOfFirst { it == ' ' || it == ',' || it == '\n' || it == ')' }
        var idxFromRemove = idx

        if (indexFinish != -1) {
            idxFromRemove += indexFinish
            return this.removeRange(idxFromRemove, this.length)
        }
    }

    return this
}

@OptIn(KspExperimental::class)
internal fun KSValueParameter.getDefaultValue(resolver: Resolver): DefaultValue? {
    if (!hasDefault) return null

    /*
        This is not ideal: having to read the first n lines of the file,
        and parse the default value manually from the source code
        I haven't found a better way yet, seems like there is no other
        way in KSP :/
     */

    if (location is NonExistLocation) {
        throw IllegalArgumentException("Cannot detect default value for Nativeblocks argument '${name!!.asString()}' because we don't have access to source code.")
    }

    val fileLocation = location as FileLocation
    val (line, imports) = File(fileLocation.filePath).readLineAndImports(fileLocation.lineNumber)

    return DefaultParameterValueReader.readDefaultValue(
        { pckg, name ->
            kotlin.runCatching {
                resolver.getDeclarationsFromPackage(pckg)
                    .firstOrNull { it.simpleName.asString().contains(name) }
                    ?.let { ResolvedSymbol(it.isPublic() || it.isInternal()) }
            }.getOrNull()
        },
        line,
        this.containingFile!!.packageName.asString(),
        imports,
        name!!.asString(),
        type.toString(),
    )
}

internal class ResolvedSymbol(val isAccessible: Boolean)

internal data class DefaultValue(
    val code: String,
    val imports: List<String> = emptyList(),
)

private fun ResolvedSymbol?.existsAndIsAccessible() = this != null && this.isAccessible
private fun ResolvedSymbol?.existsAndIsPrivate() = this != null && !this.isAccessible

private fun File.readLineAndImports(lineNumber: Int): Pair<String, List<String>> {
    val bufferedReader = BufferedReader(InputStreamReader(FileInputStream(this), Charsets.UTF_8))
    return bufferedReader
        .useLines { lines: Sequence<String> ->
            val firstNLines = lines.take(lineNumber)

            val iterator = firstNLines.iterator()
            var line = iterator.next()
            val importsList = mutableListOf<String>()
            while (iterator.hasNext()) {
                line = iterator.next()
                if (line.startsWith("import")) {
                    importsList.add(line.removePrefix("import "))
                }
            }

            line to importsList
        }
}

private fun String.removeFromTo(from: String, to: String): String {
    val startIndex = indexOf(from)
    val endIndex = indexOf(to) + to.length

    return kotlin.runCatching { removeRange(startIndex, endIndex) }.getOrNull() ?: this
}