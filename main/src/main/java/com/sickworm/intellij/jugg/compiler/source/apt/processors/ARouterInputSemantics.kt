package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.google.gson.Gson
import com.sickworm.intellij.jugg.compiler.source.apt.AptSemanticResolver
import com.sickworm.intellij.jugg.compiler.source.apt.AptSourceAnalysis
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.AnnotationNode
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import java.io.File

/** Projects current source and baseline class annotations onto the inputs consumed by ARouter. */
internal class ARouterInputSemantics(private val analysis: AptSourceAnalysis, private val resolver: AptSemanticResolver,
    private val options: Map<String, String>) {
    private val gson = Gson()
    private var baselineTypes = emptyMap<String, ClassNode>()
    private val defaults = mutableMapOf<String, Map<String, Any>>()

    fun current(file: File): Map<String, String> {
        val facts = analysis.facts(file)
        val result = sortedMapOf<String, String>()
        facts.declarations.forEach { type ->
            val name = if (facts.packageName.isEmpty()) type.name else "${facts.packageName}.${type.name}"
            val annotations = type.annotations.mapNotNull { annotation(file, name, it) }.toMap()
            val fields = type.fields.mapNotNull { field ->
                val annotation = field.annotations.firstNotNullOfOrNull { annotation(file, name, it)?.takeIf { it.first == "Autowired" } }
                    ?: return@mapNotNull null
                check(!facts.kotlin || field.annotations.any { it.name == "JvmField" || it.name == "kotlin.jvm.JvmField" }) {
                    "Kotlin Autowired without an explicit JVM field is not recoverable: $file:${field.name}"
                }
                check(field.annotations.none { it.target in listOf("get", "set", "property") }) { "Unsupported Kotlin annotation target in $file" }
                Field(field.name, resolver.descriptor(field.type, file, name), resolver.genericSignature(field.type, file, name), annotation.second)
            }
            project(name, annotations, fields, false, file, result)
        }
        return result
    }

    fun baseline(classes: List<ClassNode>, originalClasses: List<ClassNode> = classes): Map<String, String> {
        baselineTypes = originalClasses.associateBy { it.name.replace('/', '.') }
        val result = sortedMapOf<String, String>()
        classes.forEach { type ->
            val annotations = (type.visibleAnnotations.orEmpty() + type.invisibleAnnotations.orEmpty()).mapNotNull(::classAnnotation).toMap()
            val fields = type.fields.mapNotNull { field ->
                val annotation = (field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty())
                    .firstNotNullOfOrNull { classAnnotation(it)?.takeIf { it.first == "Autowired" } } ?: return@mapNotNull null
                Field(field.name, field.desc, field.signature, annotation.second)
            }
            project(type.name.replace('/', '.'), annotations, fields, true, null, result)
        }
        return result
    }

    private data class Field(val name: String, val descriptor: String, val signature: String?, val annotation: Map<String, Any>)

    /** Keeps only facts consumed by route classification, registration and field injection. */
    private fun project(name: String, annotations: Map<String, Map<String, Any>>, fields: List<Field>, old: Boolean,
        file: File?, result: MutableMap<String, String>) {
        if (annotations.isEmpty() && fields.isEmpty()) return
        val ancestors = ancestors(name, old, file, linkedSetOf())
        val kind = routeKind(ancestors)
        annotations["Route"]?.let { route ->
            val providers = if (kind == "PROVIDER") directInterfaces(name, old, file)
                .filter { PROVIDER in ancestors(it, old, file, linkedSetOf()) }.map { if (it == PROVIDER) name else it }.sorted() else emptyList()
            result["$name@Route"] = gson.toJson(listOf(normalize("Route", route), kind, providers))
        }
        annotations["Interceptor"]?.let { result["$name@Interceptor"] = gson.toJson(listOf(normalize("Interceptor", it), INTERCEPTOR in ancestors)) }
        fields.forEach { field ->
            val fieldKind = fieldKind(field.descriptor, old)
            val effectiveName = if (fieldKind == "PROVIDER") "" else field.name
            result["$name#${field.name}"] = gson.toJson(listOf(field.descriptor, field.signature.orEmpty(), normalize("Autowired", field.annotation, effectiveName), kind, fieldKind))
        }
        if (annotations.containsKey("Route") && kind in listOf("ACTIVITY", "FRAGMENT")) {
            inheritedFields(name, old, file, linkedSetOf()).forEach { field ->
                result["$name@Param#${field.name}"] = gson.toJson(listOf(field.descriptor, normalize("Autowired", field.annotation, field.name), fieldKind(field.descriptor, old)))
            }
        }
    }

    private fun annotation(file: File, owner: String, node: AptSourceAnalysis.Annotation): Pair<String, Map<String, Any>>? {
        val facts = analysis.facts(file)
        val fq = facts.imports[node.name] ?: when {
            node.name.startsWith(PACKAGE) -> node.name
            PACKAGE in facts.stars || facts.packageName == PACKAGE -> "$PACKAGE.${node.name}"
            else -> return null
        }
        if (!fq.startsWith("$PACKAGE.")) return null
        val name = fq.substringAfterLast('.')
        if (name !in NAMES) return null
        check(node.arguments.keys.none { it.startsWith('#') }) { "Unnamed ARouter argument in $file" }
        val values = defaultValues(name).toMutableMap()
        node.arguments.forEach { (key, expression) -> values[key] = resolver.value(expression, file, owner) }
        check(values.keys.containsAll(REQUIRED.getValue(name))) { "Incomplete $name annotation in $file" }
        return name to values
    }

    private fun classAnnotation(node: AnnotationNode): Pair<String, Map<String, Any>>? {
        val name = node.desc.removePrefix("L").removeSuffix(";").replace('/', '.')
        if (!name.startsWith("$PACKAGE.")) return null
        val short = name.substringAfterLast('.')
        if (short !in NAMES) return null
        val values = defaultValues(short).toMutableMap()
        node.values.orEmpty().chunked(2).forEach { values[it[0] as String] = it[1] }
        check(values.keys.containsAll(REQUIRED.getValue(short))) { "Incomplete baseline $short annotation" }
        return short to values
    }

    private fun defaultValues(name: String): Map<String, Any> = defaults.getOrPut(name) {
        val info = resolver.classInfo("$PACKAGE.$name") ?: error("Missing ARouter annotation definition $name")
        check(info.methods.filter { it.name != "<clinit>" }.map { it.name }.toSet() == SCHEMA.getValue(name)) {
            "Unknown ARouter annotation contract $name"
        }
        info.methods.filter { it.annotationDefault != null }.associate { it.name to it.annotationDefault }
    }

    private fun normalize(name: String, values: Map<String, Any>, fieldName: String = ""): Map<String, Any> {
        val result = values.toSortedMap().mapValues { (_, value) -> when (value) { is Number -> value.toLong(); is Char -> value.code.toLong(); else -> value } }.toMutableMap()
        if (options["AROUTER_GENERATE_DOC"] != "enable") {
            if (name == "Route" || name == "Interceptor") result.remove("name")
            if (name == "Autowired") result.remove("desc")
        }
        if (name == "Autowired" && result["name"] == "") result["name"] = fieldName
        if (name == "Route" && result["group"] == "") result["group"] = (result.getValue("path") as String).substringAfter('/').substringBefore('/')
        return result.toSortedMap()
    }

    private fun directParents(name: String, old: Boolean, file: File?): List<String> {
        if (!old) {
            val source = file?.takeIf { analysis.facts(it).declarations.any { declaration -> (if (analysis.facts(it).packageName.isEmpty()) declaration.name else "${analysis.facts(it).packageName}.${declaration.name}") == name } } ?: resolver.source(name)
            if (source != null) {
                val facts = analysis.facts(source)
                val type = facts.declarations.firstOrNull { (if (facts.packageName.isEmpty()) it.name else "${facts.packageName}.${it.name}") == name }
                if (type != null) return type.parents.map { resolver.qualified(it.substringBefore('<'), source, name) }
            }
        }
        val info = (if (old) baselineTypes[name] else null) ?: resolver.classInfo(name) ?: error("Missing ${if (old) "baseline" else "current"} parent type $name")
        return (listOfNotNull(info.superName) + info.interfaces.orEmpty()).map { it.replace('/', '.') }
    }

    private fun directInterfaces(name: String, old: Boolean, file: File?): List<String> {
        if (!old) {
            val source = resolver.source(name) ?: file
            if (source != null) {
                val facts = analysis.facts(source)
                val type = facts.declarations.firstOrNull { name == if (facts.packageName.isEmpty()) it.name else "${facts.packageName}.${it.name}" }
                if (type != null) {
                    check(type.interfaces.none { it.contains('<') }) { "Generic provider interfaces cannot be compared reliably: $name" }
                    return type.interfaces.map { resolver.qualified(it, source, name) }
                }
            }
        }
        val info = (if (old) baselineTypes[name] else null) ?: resolver.classInfo(name) ?: error("Missing provider interfaces of $name")
        check(info.signature?.contains('<') != true) { "Generic baseline provider interfaces cannot be compared reliably: $name" }
        return info.interfaces.orEmpty().map { it.replace('/', '.') }
    }

    private fun ancestors(name: String, old: Boolean, file: File?, visiting: MutableSet<String>): Set<String> {
        check(visiting.add(name)) { "Cyclic type relation $name" }
        if (name in TERMINALS) return setOf(name)
        val parents = directParents(name, old, file)
        val result = setOf(name) + parents.flatMap { ancestors(it, old, file, visiting.toMutableSet()) }
        return result
    }

    private fun inheritedFields(name: String, old: Boolean, file: File?, visiting: MutableSet<String>): List<Field> {
        if (name in TERMINALS) return emptyList()
        check(visiting.add(name)) { "Cyclic fields in $name" }
        val source = if (old) null else resolver.source(name) ?: file
        val own = if (source != null) currentFields(name, source) else {
            val info = (if (old) baselineTypes[name] else null) ?: resolver.classInfo(name) ?: error("Missing inherited fields in $name")
            info.fields.mapNotNull { field ->
                val annotation = (field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty())
                    .firstNotNullOfOrNull { classAnnotation(it)?.takeIf { it.first == "Autowired" } } ?: return@mapNotNull null
                Field(field.name, field.desc, field.signature, annotation.second)
            }
        }
        return own + directParents(name, old, file).flatMap { inheritedFields(it, old, null, visiting.toMutableSet()) }
    }

    private fun currentFields(name: String, file: File): List<Field> {
        val facts = analysis.facts(file)
        val type = facts.declarations.firstOrNull { name == if (facts.packageName.isEmpty()) it.name else "${facts.packageName}.${it.name}" }
            ?: error("Missing source declaration $name in $file")
        return type.fields.mapNotNull { field ->
            val annotation = field.annotations.firstNotNullOfOrNull { annotation(file, name, it)?.takeIf { it.first == "Autowired" } }
                ?: return@mapNotNull null
            Field(field.name, resolver.descriptor(field.type, file, name), resolver.genericSignature(field.type, file, name), annotation.second)
        }
    }

    private fun fieldKind(descriptor: String, old: Boolean): String {
        SIMPLE_FIELDS[descriptor]?.let { return it }
        if (descriptor.startsWith('[')) return "SERIALIZABLE"
        val name = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
        val parents = ancestors(name, old, null, linkedSetOf())
        return when {
            PROVIDER in parents -> "PROVIDER"
            "android.os.Parcelable" in parents -> "PARCELABLE"
            "java.io.Serializable" in parents -> "SERIALIZABLE"
            else -> "OBJECT"
        }
    }

    private fun routeKind(ancestors: Set<String>): String = when {
        "android.app.Activity" in ancestors -> "ACTIVITY"
        ancestors.any { it in FRAGMENTS } -> "FRAGMENT"
        PROVIDER in ancestors -> "PROVIDER"
        "android.app.Service" in ancestors -> "SERVICE"
        else -> "UNKNOWN"
    }

    companion object {
        const val PACKAGE = "com.alibaba.android.arouter.facade.annotation"
        private const val PROVIDER = "com.alibaba.android.arouter.facade.template.IProvider"
        private const val INTERCEPTOR = "com.alibaba.android.arouter.facade.template.IInterceptor"
        private val FRAGMENTS = setOf("android.app.Fragment", "androidx.fragment.app.Fragment", "android.support.v4.app.Fragment")
        private val TERMINALS = FRAGMENTS + setOf("java.lang.Object", "android.app.Activity", "android.app.Service", "java.io.Serializable", "android.os.Parcelable", PROVIDER, INTERCEPTOR)
        private val SIMPLE_FIELDS = mapOf("Z" to "BOOLEAN", "B" to "BYTE", "S" to "SHORT", "C" to "CHAR", "I" to "INT", "J" to "LONG", "F" to "FLOAT", "D" to "DOUBLE",
            "Ljava/lang/Boolean;" to "BOOLEAN", "Ljava/lang/Byte;" to "BYTE", "Ljava/lang/Short;" to "SHORT", "Ljava/lang/Character;" to "CHAR",
            "Ljava/lang/Integer;" to "INT", "Ljava/lang/Long;" to "LONG", "Ljava/lang/Float;" to "FLOAT", "Ljava/lang/Double;" to "DOUBLE", "Ljava/lang/String;" to "STRING")
        private val NAMES = setOf("Route", "Autowired", "Interceptor")
        private val REQUIRED = mapOf("Route" to setOf("path"), "Autowired" to emptySet(), "Interceptor" to setOf("priority"))
        private val SCHEMA = mapOf("Route" to setOf("path", "group", "name", "extras", "priority"),
            "Autowired" to setOf("name", "required", "desc"), "Interceptor" to setOf("priority", "name"))
    }
}
