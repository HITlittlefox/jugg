package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.source.apt.AptSemanticResolver
import com.sickworm.intellij.jugg.compiler.source.apt.AptSourceAnalysis
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** Recovers SourceFile identities from original classes and checks their matching generated baseline. */
internal class ARouterBaseline {
    private var key: String? = null
    private var classes: List<ClassNode> = emptyList()
    private var missingSourceIdentities: List<String> = emptyList()

    fun recover(context: ICompileContext, module: ModuleInfo, file: File, baseline: String, generated: Map<String, File>,
        analysis: AptSourceAnalysis, resolver: AptSemanticResolver, semantics: ARouterInputSemantics): Map<String, String> {
        if (generated.isEmpty()) {
            val facts = analysis.facts(file)
            val old = facts.declarations.mapNotNull { declaration ->
                resolver.classInfo(if (facts.packageName.isEmpty()) declaration.name else "${facts.packageName}.${declaration.name}")
            }
            check(old.none(::hasAnnotations)) { "Generated ARouter baseline is missing for an existing annotated class in $file" }
            return emptyMap()
        }
        check(!hasRelatedOverlay(context, module)) { "No input record for the deployed overlay; Gradle classes cannot prove its annotation values" }
        val version = "${module.moduleRootDir.canonicalPath}:${module.buildVariant}:$baseline"
        if (key != version) {
            val files = module.buildPathInfo.allClassPath.filter { it.isDirectory }.flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
            }
            check(files.isNotEmpty()) { "Original class files are missing for the Gradle ARouter baseline" }
            val restored = files.map { source -> ClassNode().also { ClassReader(source.readBytes()).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES) } }
            validateGenerated(generated.values.toList(), restored, analysis, resolver, semantics)
            missingSourceIdentities = restored.filter { type -> type.sourceFile == null &&
                hasAnnotations(type) }.map { it.name }
            classes = restored
            key = version
        }
        check(missingSourceIdentities.isEmpty()) { "Original annotation SourceFile identities are missing: $missingSourceIdentities" }
        val identities = if (analysis.mentions(file, ARouterInputSemantics.PACKAGE)) {
            val facts = analysis.facts(file)
            facts.declarations.map { (if (facts.packageName.isEmpty()) it.name else "${facts.packageName}.${it.name}").replace('.', '/') }
        } else emptyList()
        val matched = classes.filter { it.sourceFile == file.name || it.name in identities }
        val packages = matched.map { it.name.substringBeforeLast('/', "") }.distinct()
        check(packages.size <= 1) { "Ambiguous SourceFile identity ${file.name}: $packages" }
        return semantics.baseline(matched, classes)
    }

    private fun hasRelatedOverlay(context: ICompileContext, module: ModuleInfo): Boolean {
        val outputs = context.deployedFiles.filter { it.type in listOf(CompileOutput.Type.Dex, CompileOutput.Type.Class) }
        if (outputs.isEmpty()) return false
        val related = linkedSetOf<String>()
        val pending = java.util.ArrayDeque<ModuleInfo>().apply { add(module) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!related.add(current.name)) continue
            current.moduleDependencies.mapNotNull { context.modules[it.moduleName] }.forEach { pending.add(it) }
        }
        return outputs.any { it.relativeModule == null || it.relativeModule.name in related || it.relativeModule == context.tempModule }
    }

    /** Recognizes the standard build contract instead of interpreting arbitrary fork argument positions. */
    private fun validateGenerated(files: List<File>, classes: List<ClassNode>, analysis: AptSourceAnalysis, resolver: AptSemanticResolver, semantics: ARouterInputSemantics) {
        val expected = semantics.baseline(classes, classes)
        val byName = classes.associateBy { it.name.replace('/', '.') }
        val annotatedRoutes = classes.filter { type -> annotations(type).any { it.desc == ROUTE } }.map { it.name.replace('/', '.') }.toSet()
        val registered = linkedSetOf<String>()
        val providerKeys = linkedMapOf<String, MutableSet<String>>()
        files.filter { it.name.contains("Group") || it.name.contains("Providers") }.forEach { file ->
            val tree = analysis.java(file)
            check(tree.types.any { type -> type is com.github.javaparser.ast.body.ClassOrInterfaceDeclaration &&
                type.implementedTypes.any { it.nameAsString in listOf("IRouteGroup", "IProviderGroup") } }) {
                "Unknown ARouter generated template in $file"
            }
            val calls = tree.findAll(MethodCallExpr::class.java).filter { it.nameAsString == "build" }
            calls.forEach { call ->
                val info = resolver.classInfo("com.alibaba.android.arouter.facade.model.RouteMeta") ?: error("Missing RouteMeta contract")
                check(info.methods.any { it.name == "build" && it.desc == BUILD }) { "Unknown RouteMeta.build contract in $file" }
                check(call.arguments.size == 7 && call.getArgument(1) is ClassExpr) { "Unknown ARouter build template in $file" }
                val destination = (call.getArgument(1) as ClassExpr).type.asString()
                val fqcn = resolver.qualified(destination, file)
                val type = byName[fqcn] ?: error("Missing original class $fqcn registered in $file")
                check(annotations(type).any { it.desc == ROUTE }) { "Missing original Route annotation on $fqcn" }
                validateRoute(call, fqcn, file, expected, resolver)
                if (file.name.contains("Providers")) {
                    val registration = call.parentNode.get() as MethodCallExpr
                    providerKeys.getOrPut(fqcn) { linkedSetOf() } += registration.getArgument(0).asStringLiteralExpr().asString()
                }
                registered += fqcn
            }
        }
        val expectedProviders = expected.filterKeys { it.endsWith("@Route") }.mapValues { JsonParser.parseString(it.value).asJsonArray[2].asJsonArray.map { name -> name.asString }.toSet() }
            .filterValues { it.isNotEmpty() }.mapKeys { it.key.removeSuffix("@Route") }
        check(providerKeys == expectedProviders) { "Class and generated provider registration sets disagree" }
        validateInterceptors(files, expected, analysis, resolver)
        validateAutowired(files, expected, analysis, resolver)
        check(registered == annotatedRoutes) { "Original class and generated route identities disagree: classes=$annotatedRoutes, generated=$registered" }
    }

    private fun validateRoute(call: MethodCallExpr, name: String, file: File, expected: Map<String, String>, resolver: AptSemanticResolver) {
        val projection = JsonParser.parseString(expected.getValue("$name@Route")).asJsonArray
        val values = projection[0].asJsonObject
        val generated = listOf(call.getArgument(2).asStringLiteralExpr().asString(), call.getArgument(3).asStringLiteralExpr().asString(),
            resolver.value(call.getArgument(5).toString(), file, ""), resolver.value(call.getArgument(6).toString(), file, "")).map { it.toString() }
        val original = listOf(values["path"].asString.lowercase(), values["group"].asString.lowercase(),
            values["priority"].asLong.toString(), values["extras"].asLong.toString())
        check(generated == original && call.getArgument(0).toString().substringAfterLast('.') == projection[1].asString) {
            "Class and generated route semantics disagree for $name"
        }
        val parameterKinds = expected.filterKeys { it.startsWith("$name@Param#") }.values.map { JsonParser.parseString(it).asJsonArray }
            .filter { it[2].asString != "PROVIDER" }.associate { it[1].asJsonObject["name"].asString to KINDS.indexOf(it[2].asString) }
        val generatedKinds = call.getArgument(4).findAll(MethodCallExpr::class.java).filter { it.nameAsString == "put" }
            .associate { it.getArgument(0).asStringLiteralExpr().asString() to resolver.value(it.getArgument(1).toString(), file, "").toString().toInt() }
        check(generatedKinds == parameterKinds) { "Class and generated route parameter types disagree for $name" }
        if (file.name.contains("Providers")) {
            val registration = call.parentNode.orElse(null) as? MethodCallExpr ?: error("Unknown provider registration template in $file")
            check(registration.nameAsString == "put" && registration.getArgument(0).asStringLiteralExpr().asString() in projection[2].asJsonArray.map { it.asString }) {
                "Class and generated provider interfaces disagree for $name"
            }
        }
    }

    private fun validateInterceptors(files: List<File>, expected: Map<String, String>, analysis: AptSourceAnalysis, resolver: AptSemanticResolver) {
        val original = expected.filterKeys { it.endsWith("@Interceptor") }.mapValues {
            val projection = JsonParser.parseString(it.value).asJsonArray
            check(projection[1].asBoolean) { "Original interceptor type disagrees with the generated baseline" }
            projection[0].asJsonObject["priority"].asLong
        }
            .mapKeys { it.key.removeSuffix("@Interceptor") }
        val generated = files.filter { it.name.contains("Interceptors") }.flatMap { file ->
            val tree = analysis.java(file)
            check(tree.types.any { type -> type is com.github.javaparser.ast.body.ClassOrInterfaceDeclaration && type.implementedTypes.any { it.nameAsString == "IInterceptorGroup" } }) {
                "Unknown interceptor template in $file"
            }
            tree.findAll(MethodCallExpr::class.java).filter { it.nameAsString == "put" }.map { call ->
                check(call.arguments.size == 2 && call.getArgument(1) is ClassExpr) { "Unknown interceptor registration in $file" }
                resolver.qualified((call.getArgument(1) as ClassExpr).type.asString(), file) to resolver.value(call.getArgument(0).toString(), file, "").toString().toLong()
            }
        }.toMap()
        check(generated == original) { "Class and generated interceptor baselines disagree" }
    }

    /** Recovers only recognizable injection templates; unknown field forms stay best-effort. */
    private fun validateAutowired(files: List<File>, expected: Map<String, String>, analysis: AptSourceAnalysis, resolver: AptSemanticResolver) {
        expected.filterKeys { it.contains('#') && !it.contains("@Param#") }.forEach { (identity, json) ->
            val owner = identity.substringBefore('#')
            val field = identity.substringAfter('#')
            val projection = JsonParser.parseString(json).asJsonArray
            val helper = files.singleOrNull { it.name == owner.substringAfterLast('.') + "\$\$ARouter\$\$Autowired.java" }
                ?: error("Missing original Autowired helper for $identity, generated=${files.map { it.name }}")
            val tree = analysis.java(helper)
            val assignment = tree.findAll(AssignExpr::class.java).singleOrNull { expression ->
                val target = expression.target as? FieldAccessExpr
                target?.scope?.toString() == "substitute" && target.nameAsString == field
            } ?: error("Unknown injection template for $identity")
            validateInjection(assignment, projection, helper, resolver, identity)
            if (projection[0].asString.startsWith('L') && projection[4].asString != "PROVIDER") {
                val nullCheck = tree.findAll(BinaryExpr::class.java).any { it.operator == BinaryExpr.Operator.EQUALS &&
                    it.left.toString() == "substitute.$field" && it.right.isNullLiteralExpr }
                check(nullCheck == projection[2].asJsonObject["required"].asBoolean) {
                    "Class and generated injection requirement disagree for $identity"
                }
            }
        }
    }

    /** Matches field reads and reference types only when the generated template exposes them explicitly. */
    private fun validateInjection(assignment: AssignExpr, projection: JsonArray, file: File, resolver: AptSemanticResolver, identity: String) {
        val calls = assignment.value.findAll(MethodCallExpr::class.java)
        val kind = projection[4].asString
        val key = projection[2].asJsonObject["name"].asString
        if (kind == "PROVIDER") {
            check(calls.any { it.nameAsString == "navigation" }) { "Unknown provider injection in $identity" }
            check(key.isNotEmpty() || calls.none { it.nameAsString == "build" }) { "Unexpected named provider injection for $identity" }
            if (key.isNotEmpty()) check(calls.any { it.nameAsString == "build" && it.arguments.first().asStringLiteralExpr().asString() == key }) {
                "Class and generated provider injection key disagree for $identity"
            }
        } else {
            val hostGetter = when (projection[3].asString) {
                "ACTIVITY" -> "getIntent"
                "FRAGMENT" -> "getArguments"
                else -> error("Unknown original injection host for $identity")
            }
            val getter = GETTERS[kind] ?: error("Unknown original injection kind $kind for $identity")
            check(calls.any { it.nameAsString == hostGetter } && calls.any { it.nameAsString in listOf(getter, getter + "Extra") &&
                it.arguments.any { argument -> argument.isStringLiteralExpr && argument.asStringLiteralExpr().asString() == key } }) {
                "Class and generated injection key/type/host disagree for $identity"
            }
        }
        if (kind !in listOf("PARCELABLE", "SERIALIZABLE", "OBJECT", "PROVIDER")) return
        val type = assignment.value.findAll(ObjectCreationExpr::class.java).firstOrNull { it.type.nameAsString == "TypeWrapper" }
            ?.type?.typeArguments?.orElse(null)?.singleOrNull()
            ?: assignment.value.findAll(CastExpr::class.java).firstOrNull()?.type
            ?: assignment.value.findAll(ClassExpr::class.java).firstOrNull()?.type
            ?: error("Unknown reference injection type for $identity")
        check(resolver.descriptor(type.asString(), file, "") == projection[0].asString &&
            resolver.genericSignature(type.asString(), file, "").orEmpty() == projection[1].asString) {
            "Class and generated reference injection type disagree for $identity"
        }
    }

    private fun hasAnnotations(type: ClassNode): Boolean = annotations(type).any { it.desc in ANNOTATIONS } ||
        type.fields.any { field -> (field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty()).any { it.desc in ANNOTATIONS } }

    private fun annotations(type: ClassNode) = type.visibleAnnotations.orEmpty() + type.invisibleAnnotations.orEmpty()

    private companion object {
        val GETTERS = mapOf("BOOLEAN" to "getBoolean", "BYTE" to "getByte", "SHORT" to "getShort", "INT" to "getInt", "LONG" to "getLong", "CHAR" to "getChar", "FLOAT" to "getFloat", "DOUBLE" to "getDouble", "STRING" to "getString", "PARCELABLE" to "getParcelable", "SERIALIZABLE" to "getSerializable", "OBJECT" to "getString")
        val KINDS = listOf("BOOLEAN", "BYTE", "SHORT", "INT", "LONG", "CHAR", "FLOAT", "DOUBLE", "STRING", "SERIALIZABLE", "PARCELABLE", "OBJECT")
        val ANNOTATIONS = setOf("Route", "Autowired", "Interceptor").map { "L${ARouterInputSemantics.PACKAGE.replace('.', '/')}/$it;" }.toSet()
        const val ROUTE = "Lcom/alibaba/android/arouter/facade/annotation/Route;"
        const val BUILD = "(Lcom/alibaba/android/arouter/facade/enums/RouteType;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;II)Lcom/alibaba/android/arouter/facade/model/RouteMeta;"
    }
}
