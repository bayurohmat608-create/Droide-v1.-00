package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

// Plugin schemas are model-facing contracts and a runtime security boundary.


object AgentPluginJsonSchema {
    private const val MAX_DEPTH = 16
    private const val MAX_PATTERN_CHARS = 256
    private const val MAX_ERROR_CHARS = 600

    private val annotations = setOf(
        "title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly", "${'$'}schema",
    )
    private val assertions = setOf(
        "type", "properties", "required", "additionalProperties", "items", "enum", "const",
        "minLength", "maxLength", "pattern", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
        "minItems", "maxItems", "minProperties", "maxProperties", "allOf", "anyOf", "oneOf", "not",
    )

    fun schemaSupported(schema: JsonObject): Boolean = runCatching {
        validateSchema(schema, depth = 0)
        true
    }.getOrDefault(false)

    fun requireValid(schema: JsonObject, input: JsonObject) {
        validateSchema(schema, depth = 0)
        val error = validateValue(schema, input, path = "${'$'}", depth = 0)
        require(error == null) { "Agent plugin input failed schema validation: ${error?.take(MAX_ERROR_CHARS)}" }
    }

    fun invocationHash(input: JsonObject): String {
        val canonical = canonicalJson(input)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun validateSchema(schema: JsonObject, depth: Int) {
        require(depth <= MAX_DEPTH) { "Agent plugin schema is too deeply nested" }
        schema.keys.forEach { key ->
            require(key in annotations || key in assertions) { "Unsupported Agent plugin schema keyword: ${'$'}key" }
        }
        schema["type"]?.let { type ->
            val names = when (type) {
                is JsonPrimitive -> listOf(type.contentOrNull ?: error("Invalid schema type"))
                is JsonArray -> type.map { (it as? JsonPrimitive)?.contentOrNull ?: error("Invalid schema type") }
                else -> error("Invalid schema type")
            }
            require(names.isNotEmpty() && names.size <= 7 && names.distinct().size == names.size &&
                names.all { it in setOf("object", "array", "string", "number", "integer", "boolean", "null") }) {
                "Unsupported Agent plugin schema type"
            }
        }
        schema["properties"]?.let { element ->
            val properties = element as? JsonObject ?: error("Schema properties must be an object")
            require(properties.size <= 128 && properties.keys.all { it.length in 1..256 }) { "Invalid schema properties" }
            properties.values.forEach { child ->
                validateSchema(child as? JsonObject ?: error("Schema property must be an object"), depth + 1)
            }
        }
        schema["required"]?.let { required ->
            require(required is JsonArray && required.size <= 128) { "Invalid schema required list" }
            val names = required.map { (it as? JsonPrimitive)?.contentOrNull ?: error("Invalid required property") }
            require(names.distinct().size == names.size && names.all { it.length in 1..256 }) { "Invalid required property" }
        }
        schema["additionalProperties"]?.let { additional ->
            require(additional is JsonPrimitive && additional.booleanOrNull != null || additional is JsonObject) {
                "Invalid additionalProperties schema"
            }
            if (additional is JsonObject) validateSchema(additional, depth + 1)
        }
        schema["items"]?.let { validateSchema(it as? JsonObject ?: error("Schema items must be an object"), depth + 1) }
        schema["enum"]?.let { element ->
            require(element is JsonArray && element.size in 1..128) { "Invalid schema enum" }
        }
        listOf("allOf", "anyOf", "oneOf").forEach { keyword ->
            schema[keyword]?.let { element ->
                require(element is JsonArray && element.size in 1..16) { "Invalid ${'$'}keyword schema" }
                element.forEach { validateSchema(it as? JsonObject ?: error("Invalid ${'$'}keyword branch"), depth + 1) }
            }
        }
        schema["not"]?.let { validateSchema(it as? JsonObject ?: error("Invalid not schema"), depth + 1) }
        schema["pattern"]?.let {
            val pattern = (it as? JsonPrimitive)?.contentOrNull ?: error("Invalid pattern")
            require(pattern.length <= MAX_PATTERN_CHARS) { "Schema pattern is too large" }
            runCatching { Regex(pattern) }.getOrElse { error("Invalid schema pattern") }
        }
        listOf("minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties").forEach { keyword ->
            schema[keyword]?.let { require(it.jsonPrimitive.intOrNull?.let { value -> value >= 0 } == true) { "Invalid ${'$'}keyword" } }
        }
        listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum").forEach { keyword ->
            schema[keyword]?.let { require(it.jsonPrimitive.doubleOrNull?.isFinite() == true) { "Invalid ${'$'}keyword" } }
        }
    }

    private fun validateValue(schema: JsonObject, value: JsonElement, path: String, depth: Int): String? {
        if (depth > MAX_DEPTH) return "${'$'}path exceeded validation depth"
        if (!matchesType(schema["type"], value)) return "${'$'}path has the wrong type"
        (schema["enum"] as? JsonArray)?.let { if (it.none { candidate -> candidate == value }) return "${'$'}path is not in enum" }
        schema["const"]?.let { if (it != value) return "${'$'}path does not match const" }

        listOf("allOf", "anyOf", "oneOf").forEach { keyword ->
            val branches = schema[keyword] as? JsonArray ?: return@forEach
            val matches = branches.count { branch -> validateValue(branch as JsonObject, value, path, depth + 1) == null }
            if (keyword == "allOf" && matches != branches.size) return "${'$'}path does not satisfy allOf"
            if (keyword == "anyOf" && matches == 0) return "${'$'}path does not satisfy anyOf"
            if (keyword == "oneOf" && matches != 1) return "${'$'}path does not satisfy exactly one oneOf branch"
        }
        (schema["not"] as? JsonObject)?.let { if (validateValue(it, value, path, depth + 1) == null) return "${'$'}path matches forbidden schema" }

        when (value) {
            is JsonObject -> validateObject(schema, value, path, depth)?.let { return it }
            is JsonArray -> validateArray(schema, value, path, depth)?.let { return it }
            is JsonPrimitive -> if (value.isString) validateString(schema, value.content, path)?.let { return it }
            else -> Unit
        }
        if (value is JsonPrimitive && !value.isString) validateNumber(schema, value, path)?.let { return it }
        return null
    }

    private fun validateObject(schema: JsonObject, value: JsonObject, path: String, depth: Int): String? {
        val min = schema["minProperties"]?.jsonPrimitive?.intOrNull
        val max = schema["maxProperties"]?.jsonPrimitive?.intOrNull
        if (min != null && value.size < min) return "${'$'}path has fewer than ${'$'}min properties"
        if (max != null && value.size > max) return "${'$'}path has more than ${'$'}max properties"
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        required.firstOrNull { it !in value }?.let { return "${'$'}path is missing required property '${'$'}it'" }
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        value.forEach { (name, child) ->
            val childSchema = properties[name] as? JsonObject
            if (childSchema != null) {
                validateValue(childSchema, child, "${'$'}path.${'$'}name", depth + 1)?.let { return it }
            } else when (val additional = schema["additionalProperties"]) {
                is JsonPrimitive -> if (additional.booleanOrNull == false) return "${'$'}path contains unexpected property '${'$'}name'"
                is JsonObject -> validateValue(additional, child, "${'$'}path.${'$'}name", depth + 1)?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun validateArray(schema: JsonObject, value: JsonArray, path: String, depth: Int): String? {
        val min = schema["minItems"]?.jsonPrimitive?.intOrNull
        val max = schema["maxItems"]?.jsonPrimitive?.intOrNull
        if (min != null && value.size < min) return "${'$'}path has fewer than ${'$'}min items"
        if (max != null && value.size > max) return "${'$'}path has more than ${'$'}max items"
        val itemSchema = schema["items"] as? JsonObject ?: return null
        value.forEachIndexed { index, child ->
            validateValue(itemSchema, child, "${'$'}path[${'$'}index]", depth + 1)?.let { return it }
        }
        return null
    }

    private fun validateString(schema: JsonObject, value: String, path: String): String? {
        val min = schema["minLength"]?.jsonPrimitive?.intOrNull
        val max = schema["maxLength"]?.jsonPrimitive?.intOrNull
        if (min != null && value.length < min) return "${'$'}path is shorter than ${'$'}min characters"
        if (max != null && value.length > max) return "${'$'}path is longer than ${'$'}max characters"
        val pattern = schema["pattern"]?.jsonPrimitive?.contentOrNull
        if (pattern != null) {
            if (value.length > 8_000) return "${'$'}path is too large for pattern validation"
            if (!Regex(pattern).containsMatchIn(value)) return "${'$'}path does not match pattern"
        }
        return null
    }

    private fun validateNumber(schema: JsonObject, value: JsonPrimitive, path: String): String? {
        val number = value.doubleOrNull ?: return null
        schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (number < it) return "${'$'}path is below minimum" }
        schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if (number > it) return "${'$'}path is above maximum" }
        schema["exclusiveMinimum"]?.jsonPrimitive?.doubleOrNull?.let { if (number <= it) return "${'$'}path is below exclusiveMinimum" }
        schema["exclusiveMaximum"]?.jsonPrimitive?.doubleOrNull?.let { if (number >= it) return "${'$'}path is above exclusiveMaximum" }
        return null
    }

    private fun matchesType(type: JsonElement?, value: JsonElement): Boolean {
        if (type == null) return true
        val names = when (type) {
            is JsonPrimitive -> listOfNotNull(type.contentOrNull)
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> return false
        }
        return names.any { name ->
            when (name) {
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "string" -> value is JsonPrimitive && value.isString
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "integer" -> value is JsonPrimitive && !value.isString && value.contentOrNull?.toLongOrNull() != null
                "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                "null" -> value is JsonNull
                else -> false
            }
        }
    }

    private fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, child) ->
            JsonPrimitive(key).toString() + ":" + canonicalJson(child)
        }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        else -> value.toString()
    }
}
