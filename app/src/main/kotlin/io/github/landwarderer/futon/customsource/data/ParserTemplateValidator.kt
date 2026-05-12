package io.github.landwarderer.futon.customsource.data

import org.json.JSONException
import org.json.JSONObject

/**
 * Validates an imported parser template JSON string against the required schema.
 *
 * Required top-level fields: name, version, type
 * Required sections: mangaList, pageList
 */
object ParserTemplateValidator {

    sealed class Result {
        data class Valid(
            val name: String,
            val version: String,
            val type: String,
        ) : Result()

        data class Invalid(val reason: String) : Result()
    }

    fun validate(json: String): Result {
        if (json.isBlank()) {
            return Result.Invalid("The file is empty.")
        }

        val obj = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return Result.Invalid("Invalid JSON — could not parse the file: ${e.message}")
        }

        val name = obj.optString("name").trim()
        if (name.isEmpty()) {
            return Result.Invalid("Missing required field: \"name\"")
        }

        val version = obj.optString("version").trim()
        if (version.isEmpty()) {
            return Result.Invalid("Missing required field: \"version\"")
        }

        val type = obj.optString("type").trim()
        if (type.isEmpty()) {
            return Result.Invalid("Missing required field: \"type\"")
        }

        if (!obj.has("mangaList")) {
            return Result.Invalid("Missing required section: \"mangaList\"")
        }

        if (!obj.has("pageList")) {
            return Result.Invalid("Missing required section: \"pageList\"")
        }

        return Result.Valid(name = name, version = version, type = type)
    }
}
