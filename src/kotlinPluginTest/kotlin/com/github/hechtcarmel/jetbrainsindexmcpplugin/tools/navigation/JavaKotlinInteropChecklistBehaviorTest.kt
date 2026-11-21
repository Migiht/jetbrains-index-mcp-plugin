package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.SyncFilesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path

/**
 * Cross-language search checklist: every case from the Java/Kotlin interop audit
 * (ide-index-tests/checklist.json, ids kept in the failure messages) run end to end through
 * the MCP tools on one mixed source root, where Java and Kotlin reference each other in both
 * directions. Expectations encode correct IDE semantics (cross-checked against jdtls for Java
 * and kotlin-lsp for Kotlin), so each case fails if its lookup regresses.
 */
class JavaKotlinInteropChecklistBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    private companion object {
        const val SRC = "interop-src"
        const val PKG = "$SRC/idxtest"
        val FIXTURE: Path = Path.of("src/test/testData/javaKotlinInterop/idxtest")
        val PKG_PATHS = listOf("$PKG/**")
        val JSON = Json { ignoreUnknownKeys = true }
    }

    /**
     * Mock JDK + the IDE-bundled kotlin-stdlib: without the JDK Kotlin cannot see `Object`/`Enum`
     * supertypes of Java classes, and without the stdlib `@JvmStatic`/`@JvmField` are unresolved,
     * so the Java view of Kotlin declarations would not match a real project.
     */
    private object InteropDescriptor : DefaultLightProjectDescriptor({ IdeaTestUtil.getMockJdk21() }) {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            model.getModuleExtension(com.intellij.openapi.roots.LanguageLevelModuleExtension::class.java)
                ?.languageLevel = LanguageLevel.JDK_1_8
            val lib = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib")
            assertTrue("Bundled kotlin-stdlib required in $lib", Files.isRegularFile(lib.resolve("kotlin-stdlib.jar")))
            PsiTestUtil.addLibrary(model, "kotlin-stdlib", lib.toString(), "kotlin-stdlib.jar")
        }
    }

    override fun getProjectDescriptor(): LightProjectDescriptor = InteropDescriptor

    private val failures = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot(SRC)
        Files.list(FIXTURE).use { files ->
            files.sorted().forEach { writeProjectFile("$PKG/${it.fileName}", Files.readString(it)) }
        }
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    /** 1-based position of the [occurrence]-th whole-word [token] on [line] of fixture [file]. */
    private fun pos(file: String, line: Int, token: String, occurrence: Int = 1): JsonObjectBuilder.() -> Unit {
        val text = Files.readAllLines(FIXTURE.resolve(file))[line - 1]
        val hits = Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").findAll(text).toList()
        val column = hits[occurrence - 1].range.first + 1
        return { put("file", "$PKG/$file"); put("line", line); put("column", column) }
    }

    private fun run(tool: McpTool, args: JsonObjectBuilder.() -> Unit): JsonObject = runBlocking {
        val result = tool.execute(project, buildJsonObject(args))
        assertToolSucceeded("${tool.name} ${buildJsonObject(args)}", result)
        JSON.parseToJsonElement(toolText(result)).jsonObject
    }

    private fun JsonElement?.str(key: String): String? = (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonElement?.arr(key: String): JsonArray = ((this as? JsonObject)?.get(key) as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonElement.loc(): String {
        val file = str("file").orEmpty()
        val base = if (file.startsWith(PKG)) file.substringAfterLast('/') else "lib:$file"
        return "$base:${jsonObject["line"]!!.jsonPrimitive.int}"
    }

    private fun check(id: String, expected: Any?, actual: Any?, ok: Boolean = expected == actual) {
        if (!ok) failures += "$id: expected $expected but was $actual"
    }

    private fun checkSuperset(id: String, expected: Collection<String>, actual: Collection<String>) =
        check(id, "⊇ $expected", actual, actual.containsAll(expected))

    private fun assertChecklist() {
        if (failures.isNotEmpty()) fail(failures.joinToString("\n", prefix = "${failures.size} checklist case(s) failed:\n"))
    }

    private fun refs(vararg extra: Pair<String, Any>, target: JsonObjectBuilder.() -> Unit): List<String> =
        run(FindUsagesTool()) {
            target()
            putJsonArray("paths") { PKG_PATHS.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            extra.forEach { (k, v) -> put(k, v.toString()) }
        }.arr("usages").map { it.loc() }.sorted()

    private fun definition(target: JsonObjectBuilder.() -> Unit): String = run(FindDefinitionTool(), target).loc()

    private fun classes(query: String, extra: JsonObjectBuilder.() -> Unit = {}): List<JsonElement> =
        run(FindClassTool()) { put("query", query); extra() }.arr("classes").toList()

    private fun callNames(direction: String, target: JsonObjectBuilder.() -> Unit): Set<String> =
        run(CallHierarchyTool()) { target(); put("direction", direction); put("depth", 1) }
            .arr("calls").map { it.str("name")!!.substringBefore('(') }.toSet()

    private fun hierarchyNames(nodes: JsonArray, key: String): List<String> =
        nodes.flatMap { listOf(it.str("name")!!) + hierarchyNames(it.arr(key), key) }

    // ── A. find_file ─────────────────────────────────────────────────────────────────────

    fun testFindFile() {
        // Decoys that only a fuzzy (unanchored) match of "K*.kt" would accept.
        writeProjectFile("$PKG/WorldTask.kt", "package idxtest\n")
        writeProjectFile("$PKG/StatisticKey.kt", "package idxtest\n")
        fun names(query: String) = run(FindFileTool()) { put("query", query); put("pageSize", 500) }
            .arr("files").filter { it.str("path")!!.startsWith(PKG) }.map { it.str("name")!! }.toSet()

        checkSuperset("F01", listOf("JCircle.java"), names("JCircle.java"))
        checkSuperset("F02", listOf("KModels.kt"), names("KModels.kt"))
        check("F03", setOf("KModels.kt", "KRect.kt", "KUser.kt", "KUtils.kt"), names("K*.kt"))
        checkSuperset("F04", listOf("JVisitorImpl.java"), names("JVI"))
        assertChecklist()
    }

    // ── B. find_class ────────────────────────────────────────────────────────────────────

    fun testFindClass() {
        fun fqns(query: String, extra: JsonObjectBuilder.() -> Unit = {}) = classes(query, extra).map { it.str("qualifiedName") }.toSet()
        fun kind(query: String) = classes(query) { put("matchMode", "exact") }.single().str("kind")

        checkSuperset("C01", listOf("idxtest.JCircle"), fqns("JCircle").filterNotNull())
        check("C01.lang", "Java", classes("JCircle").single().str("language"))
        checkSuperset("C02", listOf("idxtest.KRect"), fqns("KRect").filterNotNull())
        check("C03", "OBJECT", kind("KConfig"))
        checkSuperset("C04", listOf("idxtest.KPoint"), fqns("KPoint").filterNotNull())
        checkSuperset("C05", listOf("idxtest.KNode.Leaf"), fqns("Leaf").filterNotNull())
        check("C06", "ENUM", kind("JColor"))
        check("C07", "ANNOTATION", kind("JMarker"))
        check("C08", "INTERFACE", kind("KVisitor"))
        checkSuperset("C09", listOf("idxtest.JVisitorImpl"), fqns("JVImpl").filterNotNull())
        check("C10", setOf("idxtest.KVisitor", "idxtest.KDefaultVisitor"), fqns("K*Visitor"))
        check("C11", setOf("idxtest.KVisitor", "idxtest.KDefaultVisitor"), fqns("Visitor") { put("language", "Kotlin") })
        check("C12", setOf("idxtest.KRect"), fqns("KRect") { put("matchMode", "exact") })
        // FX06: the JvmName file facade is a real JVM class Java code imports and calls.
        val facade = classes("KUtil") { put("matchMode", "exact") }
        check("C13", setOf("idxtest.KUtil"), facade.map { it.str("qualifiedName") }.toSet())
        check("C13.file", "KUtils.kt", facade.singleOrNull()?.str("file")?.substringAfterLast('/'))
        check("C13.kind", "FILE_FACADE", facade.singleOrNull()?.str("kind"))
        checkSuperset("C14", listOf("idxtest.JUtil.Nested"), fqns("Nested").filterNotNull())
        // FX09: typealias kind
        check("C15", "TYPE_ALIAS", kind("Points"))
        checkSuperset("C16", listOf("java.util.ArrayList"),
            fqns("ArrayList") { put("scope", "project_and_libraries"); put("matchMode", "exact") }.filterNotNull())
        assertChecklist()
    }

    // ── C. find_definition ───────────────────────────────────────────────────────────────

    fun testDefinitionWithinOneLanguage() {
        check("D01", "JBase.java:17", definition(pos("JUtil.java", 15, "scale", 1)))
        check("D02", "JBase.java:21", definition(pos("JUtil.java", 15, "scale", 2)))
        check("D03", "KUtils.kt:10", definition(pos("KUtils.kt", 12, "depth")))
        check("D31", "JUtil.java:10", definition(pos("JUtil.java", 11, "local")))
        check("D32", "KUtils.kt:8", definition(pos("KUser.kt", 15, "doubled")))
        assertChecklist()
    }

    fun testDefinitionJavaToKotlin() {
        check("D04", "KRect.kt:4", definition(pos("JCircle.java", 26, "KRect", 2)))
        check("D05", "KUtils.kt:6", definition(pos("JCircle.java", 15, "square")))
        check("D06", "KUtils.kt:8", definition(pos("JCircle.java", 30, "doubled")))
        check("D07", "KRect.kt:4", definition(pos("JCircle.java", 27, "getWidth")))
        check("D08", "KRect.kt:4", definition(pos("JCircle.java", 27, "setWidth")))
        check("D09", "KModels.kt:3", definition(pos("JCircle.java", 25, "component2")))
        check("D10", "KModels.kt:11", definition(pos("JCircle.java", 20, "MAX")))
        check("D11", "KModels.kt:14", definition(pos("JCircle.java", 20, "limit")))
        check("D12", "KModels.kt:23", definition(pos("JCircle.java", 28, "make")))
        check("D13", "KModels.kt:20", definition(pos("JCircle.java", 29, "TAG")))
        check("D14", "KModels.kt:6", definition(pos("JCircle.java", 31, "Leaf")))
        val facade = definition(pos("JCircle.java", 15, "KUtil"))
        check("D15", "KUtils.kt", facade.substringBefore(':'))
        assertChecklist()
    }

    fun testDefinitionKotlinToJava() {
        check("D16", "JCircle.java:8", definition(pos("KUser.kt", 5, "JCircle")))
        check("D17", "JBase.java:10", definition(pos("KUser.kt", 6, "name")))
        check("D18", "JBase.java:25", definition(pos("KUser.kt", 7, "create")))
        check("D19", "JColor.java:4", definition(pos("KUser.kt", 8, "RED")))
        check("D20", "JUtil.java:4", definition(pos("KUser.kt", 9, "LIMIT")))
        check("D21", "JShape.java:5", definition(pos("KUser.kt", 9, "KIND")))
        check("D22", "JUtil.java:18", definition(pos("KUser.kt", 10, "Nested")))
        check("D23", "JCallback.java:5", definition(pos("KUser.kt", 12, "onEvent")))
        check("D24", "JBase.java:17", definition(pos("KUser.kt", 14, "scale")))
        check("D25", "JBase.java:6", definition(pos("KRect.kt", 4, "JBase")))
        check("D26", "JMarker.java:7", definition(pos("KRect.kt", 3, "JMarker")))
        check("D27", "JShape.java:9", definition(pos("KRect.kt", 7, "describe", 2)))
        assertChecklist()
    }

    fun testDefinitionByQualifiedNameAndLibrary() {
        check("D28", "JBase.java:25", definition { put("language", "Java"); put("symbol", "idxtest.JBase#create(String)") })
        check("D29", "KRect.kt:4", definition { put("language", "Java"); put("symbol", "idxtest.KRect") })
        check("D34", "KUtils.kt:6", definition { put("language", "Java"); put("symbol", "idxtest.KUtil#square(double)") })
        val pi = run(FindDefinitionTool(), pos("JCircle.java", 15, "PI"))
        check("D30", "PI in a library Math file", "${pi.str("symbolName")} ${pi.str("file")}",
            pi.str("symbolName") == "PI" && !pi.str("file")!!.startsWith(PKG) && pi.str("file")!!.contains("Math"))
        val list = run(FindDefinitionTool(), pos("JCircle.java", 3, "List"))
        check("D33", "List in a library file", "${list.str("symbolName")} ${list.str("file")}",
            list.str("symbolName") == "List" && !list.str("file")!!.startsWith(PKG))
        assertChecklist()
    }

    // ── D. find_references ───────────────────────────────────────────────────────────────

    fun testReferencesAcrossLanguages() {
        check("R01", listOf("KUser.kt:7"), refs(target = pos("JBase.java", 25, "create")))
        check("R02", listOf("JCircle.java:15", "KUser.kt:15"), refs(target = pos("KUtils.kt", 6, "square")))
        check("R03", listOf("JCircle.java:27", "JCircle.java:27", "KRect.kt:11", "KRect.kt:11", "KRect.kt:5"),
            refs(target = pos("KRect.kt", 4, "width")))
        // FX01: Kotlin synthetic property `circle.name` is a usage of Java getName().
        check("R04", listOf("KUser.kt:6"), refs(target = pos("JBase.java", 10, "getName")))
        check("R04.qualified", listOf("KUser.kt:6"),
            refs { put("language", "Java"); put("symbol", "idxtest.JBase#getName()") })
        check("R05", listOf("JBase.java:27", "KUser.kt:5"), refs(target = pos("JCircle.java", 5, "JCircle")))
        check("R06", listOf("JBase.java:29", "JCircle.java:26", "JCircle.java:26", "KRect.kt:10", "KUser.kt:13"),
            refs(target = pos("KRect.kt", 4, "KRect")))
        // FX03: position on the enum constant name must search the constant, not the enum constructor.
        check("R07", listOf("JColor.java:8", "JColor.java:8", "KUser.kt:8"), refs(target = pos("JColor.java", 4, "RED")))
        check("R08", listOf("JUtil.java:15", "JUtil.java:15", "KUser.kt:14"), refs(target = pos("JBase.java", 17, "scale")))
        check("R09", listOf("JUtil.java:15"), refs(target = pos("JBase.java", 21, "scale")))
        check("R10", listOf("JCircle.java:20", "KModels.kt:14"), refs(target = pos("KModels.kt", 11, "MAX")))
        check("R11", listOf("JCircle.java:29"), refs(target = pos("KModels.kt", 20, "TAG")))
        check("R12", listOf("JCircle.java:28"), refs(target = pos("KModels.kt", 23, "make")))
        check("R13", listOf("JCircle.java:30", "KUser.kt:15"), refs(target = pos("KUtils.kt", 8, "doubled")))
        check("R14", listOf("JCircle.java:25"), refs(target = pos("KModels.kt", 3, "y")))
        check("R15", listOf("JUtil.java:11"), refs(target = pos("JUtil.java", 10, "local")))
        check("R16", listOf("KUtils.kt:10", "KUtils.kt:12", "KUtils.kt:12"), refs(target = pos("KUtils.kt", 10, "node")))
        // FX02: calls that resolve to an override (JBase.area) are usages of the interface method.
        check("R17", listOf("JBase.java:18", "JBase.java:22", "JShape.java:10", "KUser.kt:15"),
            refs(target = pos("JShape.java", 7, "area")))
        check("R18", listOf("JVisitorImpl.java:3", "KUser.kt:23"), refs(target = pos("KModels.kt", 27, "KVisitor")))
        check("R21", listOf("JShape.java:10", "KUser.kt:9"), refs(target = pos("JShape.java", 5, "KIND")))
        check("R22", listOf("KUser.kt:12"), refs(target = pos("JCallback.java", 5, "onEvent")))
        check("R23", listOf("JCircle.java:29"), refs(target = pos("KModels.kt", 17, "id")))
        assertChecklist()
    }

    fun testReferencesFiltersAndQualifiedTargets() {
        val javaOnly = run(FindUsagesTool()) {
            pos("KRect.kt", 4, "KRect")()
            putJsonArray("paths") { add(kotlinx.serialization.json.JsonPrimitive("$PKG/*.java")) }
        }.arr("usages").map { it.loc() }.sorted()
        check("R19", listOf("JBase.java:29", "JCircle.java:26", "JCircle.java:26"), javaOnly)
        check("R20", listOf("KUser.kt:9"), refs { put("language", "Java"); put("symbol", "idxtest.JUtil#twice(int)") })
        check("R07.qualified", listOf("JColor.java:8", "JColor.java:8", "KUser.kt:8"),
            refs { put("language", "Java"); put("symbol", "idxtest.JColor#RED") })
        assertChecklist()
    }

    // ── E/F/G. implementations, super methods, type hierarchy ────────────────────────────

    fun testImplementations() {
        fun impls(file: String, line: Int, token: String) =
            run(FindImplementationsTool(), pos(file, line, token)).arr("implementations").map { it.loc() }.toSet()
        check("I01", setOf("JBase.java:3", "JCircle.java:5", "KRect.kt:4", "KRect.kt:10", "KUser.kt:18"), impls("JShape.java", 4, "JShape"))
        checkSuperset("I02", listOf("JCircle.java:14", "KRect.kt:5", "KRect.kt:11", "KUser.kt:19"), impls("JShape.java", 7, "area"))
        check("I03", setOf("JVisitorImpl.java:3", "KUser.kt:23"), impls("KModels.kt", 27, "KVisitor"))
        check("I04", setOf("JVisitorImpl.java:5", "KUser.kt:24"), impls("KModels.kt", 28, "visit"))
        check("I05", setOf("KModels.kt:6", "KModels.kt:7"), impls("KModels.kt", 5, "KNode"))
        check("I06", setOf("JCircle.java:5"), impls("JCallback.java", 4, "JCallback"))
        check("I07", setOf("JCircle.java:5", "KRect.kt:4", "KRect.kt:10"), impls("JBase.java", 3, "JBase"))
        check("I08", setOf("KRect.kt:7"), impls("JShape.java", 9, "describe"))
        assertChecklist()
    }

    fun testSuperMethods() {
        fun supers(file: String, line: Int, token: String) =
            run(FindSuperMethodsTool(), pos(file, line, token)).arr("hierarchy").map { it.loc() }
        check("S01", listOf("KRect.kt:5", "JBase.java:15", "JShape.java:7"), supers("KRect.kt", 11, "area"))
        check("S02", listOf("JBase.java:15", "JShape.java:7"), supers("JCircle.java", 14, "area"))
        check("S03", listOf("KModels.kt:28"), supers("JVisitorImpl.java", 5, "visit"))
        check("S04", listOf("JShape.java:9"), supers("KRect.kt", 7, "describe"))
        check("S05", listOf("JCallback.java:5"), supers("JCircle.java", 19, "onEvent"))
        assertChecklist()
    }

    fun testTypeHierarchy() {
        fun th(target: JsonObjectBuilder.() -> Unit) = run(TypeHierarchyTool(), target)
        val square = th(pos("KRect.kt", 10, "KSquare"))
        checkSuperset("T01", listOf("idxtest.KRect", "idxtest.JBase", "idxtest.JShape"), hierarchyNames(square.arr("supertypes"), "supertypes"))
        val shape = th(pos("JShape.java", 4, "JShape"))
        checkSuperset("T02", listOf("idxtest.JBase", "idxtest.JCircle", "idxtest.KRect", "idxtest.KSquare"), hierarchyNames(shape.arr("subtypes"), "subtypes"))
        val circle = th(pos("JCircle.java", 5, "JCircle"))
        checkSuperset("T03", listOf("idxtest.JBase", "idxtest.JShape", "idxtest.JCallback"), hierarchyNames(circle.arr("supertypes"), "supertypes"))
        val visitor = th(pos("KModels.kt", 27, "KVisitor"))
        check("T04", setOf("idxtest.JVisitorImpl", "idxtest.KDefaultVisitor"), hierarchyNames(visitor.arr("subtypes"), "subtypes").toSet())
        val node = th { put("className", "idxtest.KNode") }
        check("T05", setOf("idxtest.KNode.Leaf", "idxtest.KNode.Branch"), hierarchyNames(node.arr("subtypes"), "subtypes").toSet())
        assertChecklist()
    }

    // ── H. call hierarchy ────────────────────────────────────────────────────────────────

    fun testCallHierarchy() {
        check("H01", setOf("JCircle.area", "KUser.useJava"), callNames("callers", pos("KUtils.kt", 6, "square")))
        check("H02", setOf("KUser.useJava"), callNames("callers", pos("JBase.java", 25, "create")))
        checkSuperset("H03", listOf("KUtil.doubled", "KUtil.depth", "KCompanion.make", "KRect.setWidth", "KRect.getWidth", "KPoint.getX"),
            callNames("callees", pos("JCircle.java", 23, "useKotlin")))
        // FX04: `circle.name` in Kotlin is a call of the Java accessor getName().
        checkSuperset("H04", listOf("JBase.create", "JUtil.twice", "JColor.next", "Nested.hello", "JCallback.onEvent", "JBase.getName", "KUtil.square", "KUtil.doubled"),
            callNames("callees", pos("KUser.kt", 4, "useJava")))
        // FX05: depth(node.left) inside depth is a (recursive) caller of depth.
        check("H05", setOf("KUtil.depth", "JCircle.useKotlin"), callNames("callers", pos("KUtils.kt", 10, "depth")))
        check("H06", setOf("KUser.useJava"),
            callNames("callers") { put("language", "Java"); put("symbol", "idxtest.JUtil#twice(int)") })
        assertChecklist()
    }

    // ── I. search_text ───────────────────────────────────────────────────────────────────

    fun testSearchText() {
        fun hits(query: String, extra: JsonObjectBuilder.() -> Unit = {}) = run(SearchTextTool()) {
            put("query", query)
            putJsonArray("paths") { PKG_PATHS.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            extra()
        }.arr("matches").map { "${it.loc()}:${it.jsonObject["column"]!!.jsonPrimitive.int}" }.sorted()

        check("X01", listOf("JShape.java:3:47", "KUtils.kt:5:22"), hits("IDXMARK_COMMENT"))
        check("X02", listOf("JShape.java:3:47", "KUtils.kt:5:22"), hits("IDXMARK") { put("context", "comments") })
        check("X03", listOf("JUtil.java:20:21"), hits("IDXMARK") { put("context", "strings") })
        // FX08: both regex matches on JUtil.java:15 (scale(2) and scale(3)) are reported.
        check("X04", listOf("JUtil.java:15:21", "JUtil.java:15:55", "KUser.kt:14:14"), hits("scale\\(\\d+\\)") { put("regex", true) })
        check("X05", listOf("JCircle.java:15:26", "JCircle.java:30:17", "JCircle.java:32:47"), hits("KUtil.") { put("filePattern", "*.java") })
        check("X06", emptyList<String>(), hits("Visitor") { put("wholeWord", true) })
        check("X07", listOf("JUtil.java:20:21"), hits("idxmark_string") { put("caseSensitive", false) })
        assertChecklist()
    }

    // ── J. diagnostics ───────────────────────────────────────────────────────────────────

    fun testDiagnosticsSeeTheOtherLanguage() {
        fun errors(file: String) = run(GetDiagnosticsTool()) { put("file", "$PKG/$file"); put("severity", "errors") }
            .arr("problems").map { "${it.loc()} ${it.str("message")}" }
        // Every cross-language symbol must resolve; only unrelated stdlib gaps of the light test JDK may remain.
        val crossSymbols = Regex("'(KUtil|square|doubled|depth|KConfig|MAX|limit|KCompanion|make|TAG|getId|KPoint|getX|component2|KRect|getWidth|setWidth|KNode|Leaf|JCircle|name|create|JColor|RED|next|JUtil|twice|LIMIT|KIND|Nested|hello|JCallback|onEvent|scale|area|JBase|JMarker|JShape|describe)'")
        check("G01", emptyList<String>(), errors("JCircle.java").filter { crossSymbols.containsMatchIn(it) })
        check("G02", emptyList<String>(), errors("KUser.kt").filter { crossSymbols.containsMatchIn(it) })
        check("G01.rect", emptyList<String>(), errors("KRect.kt").filter { crossSymbols.containsMatchIn(it) })

        writeProjectFile("$PKG/JBroken.java", "package idxtest;\n\npublic class JBroken {\n    public double run() {\n        return KUtil.nope(1.0);\n    }\n}\n")
        writeProjectFile("$PKG/KBroken.kt", "package idxtest\n\nfun broken(): Int = JUtil.nope(1)\n")
        check("G03", "error at JBroken.java:5 about nope", errors("JBroken.java"),
            errors("JBroken.java").any { it.startsWith("JBroken.java:5 ") && it.contains("nope") })
        check("G04", "error at KBroken.kt:3 about nope", errors("KBroken.kt"),
            errors("KBroken.kt").any { it.startsWith("KBroken.kt:3 ") && it.contains("nope") })
        assertChecklist()
    }

    // ── K/L. freshness and status ────────────────────────────────────────────────────────

    fun testExternalFileNeedsSyncToBeIndexed() {
        val basePath = requireNotNull(project.basePath)
        val fresh = Path.of(basePath, PKG, "JFresh.java")
        // Written behind the IDE's back: no VFS refresh, exactly like an external agent edit.
        Files.writeString(fresh, "package idxtest;\n\npublic class JFresh {\n    public double run() {\n        return KUtil.square(2.0);\n    }\n}\n")
        check("Y01.beforeSync", emptySet<String>(), classes("JFresh").map { it.str("qualifiedName") }.toSet())

        run(SyncFilesTool()) { putJsonArray("paths") { add(kotlinx.serialization.json.JsonPrimitive(PKG)) } }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        check("Y01", setOf("idxtest.JFresh"), classes("JFresh").map { it.str("qualifiedName") }.toSet())
        check("Y02", listOf("JCircle.java:15", "JFresh.java:5", "KUser.kt:15"), refs(target = pos("KUtils.kt", 6, "square")))

        Files.delete(fresh)
        run(SyncFilesTool()) { putJsonArray("paths") { add(kotlinx.serialization.json.JsonPrimitive(PKG)) } }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        check("Y03", emptySet<String>(), classes("JFresh").map { it.str("qualifiedName") }.toSet())
        assertChecklist()
    }

    fun testIndexStatus() {
        val status = run(GetIndexStatusTool()) {}
        check("P01", "false", status["isDumbMode"]?.jsonPrimitive?.contentOrNull)
        assertChecklist()
    }
}
