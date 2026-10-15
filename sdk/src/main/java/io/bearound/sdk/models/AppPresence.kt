package io.bearound.sdk.models

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One app the host wants to check for on this device.
 *
 * Each platform identifier is optional: a target without [androidPackageName] is still
 * reported on Android, as `unknown` / [AppPresenceReason.UNSUPPORTED_PLATFORM].
 *
 * @property id host-chosen identifier, ASCII `[A-Za-z0-9._-]`, 1 to 64 characters, unique
 *   within a configuration.
 * @property iosScheme URL scheme queried on iOS (RFC 3986 scheme syntax, no `://`).
 * @property androidPackageName package name queried on Android. It must also be declared in
 *   the host manifest `<queries>` and in the build evidence asset.
 */
data class AppPresenceTarget(
    val id: String,
    val iosScheme: String? = null,
    val androidPackageName: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("iosScheme", iosScheme ?: JSONObject.NULL)
        put("androidPackageName", androidPackageName ?: JSONObject.NULL)
    }

    companion object {
        /** Parses one target. Throws [AppPresenceConfigurationException] on a wrong shape. */
        fun fromJson(json: JSONObject): AppPresenceTarget = AppPresenceTarget(
            id = AppPresenceJson.requiredString(json, "id"),
            iosScheme = AppPresenceJson.optionalString(json, "iosScheme"),
            androidPackageName = AppPresenceJson.optionalString(json, "androidPackageName")
        )
    }
}

/**
 * Opt-in configuration of the app presence feature. Disabled with no targets by default:
 * nothing is queried until the host enables it explicitly.
 */
data class AppPresenceConfiguration(
    val enabled: Boolean = false,
    val targets: List<AppPresenceTarget> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("targets", JSONArray().apply { targets.forEach { put(it.toJson()) } })
    }

    /**
     * Returns the validation error of this configuration, or `null` when it is valid.
     * See [AppPresenceValidator.validate].
     */
    fun validationError(): AppPresenceConfigurationException? = AppPresenceValidator.validate(this)

    companion object {
        /** The default: feature off, no targets. */
        val DISABLED = AppPresenceConfiguration()

        /**
         * Parses a configuration. Missing `enabled` means `false` and missing `targets` means
         * an empty list. Malformed JSON or a wrong field type throws
         * [AppPresenceConfigurationException]; the targets are NOT validated here, call
         * [validationError] for that.
         */
        fun fromJson(json: String): AppPresenceConfiguration {
            val obj = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw AppPresenceConfigurationException("configuration is not a JSON object")
            }
            return fromJson(obj)
        }

        fun fromJson(json: JSONObject): AppPresenceConfiguration {
            val enabled = when {
                !json.has("enabled") || json.isNull("enabled") -> false
                else -> json.opt("enabled") as? Boolean
                    ?: throw AppPresenceConfigurationException("enabled must be a boolean")
            }
            val targets = when {
                !json.has("targets") || json.isNull("targets") -> emptyList()
                else -> {
                    val array = json.opt("targets") as? JSONArray
                        ?: throw AppPresenceConfigurationException("targets must be an array")
                    (0 until array.length()).map { index ->
                        val item = array.opt(index) as? JSONObject
                            ?: throw AppPresenceConfigurationException("targets[$index] must be an object")
                        AppPresenceTarget.fromJson(item)
                    }
                }
            }
            return AppPresenceConfiguration(enabled = enabled, targets = targets)
        }
    }
}

/**
 * Rejected app presence configuration. Only the app presence feature is disabled when this
 * happens; the rest of the SDK keeps running. Never sent to error telemetry.
 */
class AppPresenceConfigurationException(
    message: String,
    val code: String = CODE
) : IllegalArgumentException(message) {
    companion object {
        const val CODE = "app_presence_invalid_configuration"
    }
}

/** Why a target ended up `unknown`. Serialized as [wireValue]. */
enum class AppPresenceReason(val wireValue: String) {
    NOT_DECLARED("not_declared"),
    DECLARATION_UNVERIFIED("declaration_unverified"),
    SCHEME_BUDGET_EXCEEDED("scheme_budget_exceeded"),
    UNSUPPORTED_PLATFORM("unsupported_platform"),
    QUERY_FAILED("query_failed");

    companion object {
        fun fromWireValue(value: String): AppPresenceReason? = values().firstOrNull { it.wireValue == value }
    }
}

/** Result state of one target. Serialized as [wireValue]. */
enum class AppPresenceState(val wireValue: String) {
    PRESENT("present"),
    ABSENT("absent"),
    UNKNOWN("unknown");

    companion object {
        fun fromWireValue(value: String): AppPresenceState? = values().firstOrNull { it.wireValue == value }
    }
}

/** How a target was checked. Serialized as [wireValue]. */
enum class AppPresenceDetectionMethod(val wireValue: String) {
    IOS_URL_SCHEME("ios_url_scheme"),
    ANDROID_PACKAGE("android_package"),
    NONE("none");

    companion object {
        fun fromWireValue(value: String): AppPresenceDetectionMethod? = values().firstOrNull { it.wireValue == value }
    }
}

/**
 * Result of one target in a round.
 *
 * [present] is `null` whenever [state] is `unknown`; it is never coerced to `false`.
 * [checkedAt] is the decision time, ISO 8601 UTC with milliseconds (see [AppPresenceTime]).
 */
data class AppPresenceResult(
    val targetId: String,
    val state: AppPresenceState,
    val present: Boolean?,
    val reason: AppPresenceReason?,
    val checkedAt: String,
    val detectionMethod: AppPresenceDetectionMethod
) {
    /** Serializes with `present` and `reason` always written, as JSON `null` when absent. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("targetId", targetId)
        put("state", state.wireValue)
        put("present", present ?: JSONObject.NULL)
        put("reason", reason?.wireValue ?: JSONObject.NULL)
        put("checkedAt", checkedAt)
        put("detectionMethod", detectionMethod.wireValue)
    }

    companion object {
        /** Throws [JSONException] on a wrong shape or an unknown enum value. */
        fun fromJson(json: JSONObject): AppPresenceResult {
            val state = AppPresenceState.fromWireValue(json.getString("state"))
                ?: throw JSONException("unknown state")
            val method = AppPresenceDetectionMethod.fromWireValue(json.getString("detectionMethod"))
                ?: throw JSONException("unknown detectionMethod")
            val present = if (json.isNull("present")) null else json.get("present") as? Boolean
                ?: throw JSONException("present must be a boolean or null")
            val reason = if (json.isNull("reason")) null else {
                AppPresenceReason.fromWireValue(json.getString("reason"))
                    ?: throw JSONException("unknown reason")
            }
            return AppPresenceResult(
                targetId = json.getString("targetId"),
                state = state,
                present = present,
                reason = reason,
                checkedAt = json.getString("checkedAt"),
                detectionMethod = method
            )
        }
    }
}

/**
 * A completed round: every configured target once, in the configured order.
 *
 * [cached] is delivery metadata (a replay of the last round), it does not change any time.
 */
data class AppPresenceSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val snapshotId: String,
    val configurationFingerprint: String,
    val checkedAt: String,
    val cached: Boolean,
    val results: List<AppPresenceResult>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("snapshotId", snapshotId)
        put("configurationFingerprint", configurationFingerprint)
        put("checkedAt", checkedAt)
        put("cached", cached)
        put("results", JSONArray().apply { results.forEach { put(it.toJson()) } })
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /** Throws [JSONException] on a wrong shape or an unsupported schema version. */
        fun fromJson(json: JSONObject): AppPresenceSnapshot {
            val version = json.getInt("schemaVersion")
            if (version != SCHEMA_VERSION) throw JSONException("unsupported schemaVersion $version")
            val array = json.getJSONArray("results")
            return AppPresenceSnapshot(
                schemaVersion = version,
                snapshotId = json.getString("snapshotId"),
                configurationFingerprint = json.getString("configurationFingerprint"),
                checkedAt = json.getString("checkedAt"),
                cached = json.getBoolean("cached"),
                results = (0 until array.length()).map { AppPresenceResult.fromJson(array.getJSONObject(it)) }
            )
        }
    }
}

/** ISO 8601 UTC timestamps with milliseconds, e.g. `2026-10-09T12:00:00.000Z`. */
object AppPresenceTime {
    fun format(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))
}

/** Validation, normalization and fingerprint of an [AppPresenceConfiguration]. */
object AppPresenceValidator {
    const val MAX_TARGETS = 50
    const val MAX_ID_LENGTH = 64

    private val ID_REGEX = Regex("^[A-Za-z0-9._-]{1,$MAX_ID_LENGTH}$")
    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
    private val PACKAGE_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    private val FORBIDDEN_SCHEMES = setOf("http", "https")

    fun isValidId(id: String): Boolean = ID_REGEX.matches(id)

    /** RFC 3986 scheme syntax; `http` and `https` are refused (case-insensitive). */
    fun isValidIosScheme(scheme: String): Boolean =
        SCHEME_REGEX.matches(scheme) && scheme.lowercase(Locale.ROOT) !in FORBIDDEN_SCHEMES

    /** Dot-separated identifier segments, at least two, no wildcard. Syntax only. */
    fun isValidAndroidPackageName(packageName: String): Boolean = PACKAGE_REGEX.matches(packageName)

    /**
     * Returns `null` when [configuration] is valid, otherwise the error describing the first
     * problem found. The check is the same whether the feature is enabled or not. A target
     * without a platform identifier is valid; an empty string is not.
     */
    fun validate(configuration: AppPresenceConfiguration): AppPresenceConfigurationException? {
        val targets = configuration.targets
        if (targets.size > MAX_TARGETS) {
            return AppPresenceConfigurationException("at most $MAX_TARGETS targets are allowed, got ${targets.size}")
        }
        val seen = HashSet<String>()
        targets.forEachIndexed { index, target ->
            if (!isValidId(target.id)) {
                return AppPresenceConfigurationException("targets[$index].id is invalid")
            }
            if (!seen.add(target.id)) {
                return AppPresenceConfigurationException("targets[$index].id is duplicated")
            }
            target.iosScheme?.let {
                if (!isValidIosScheme(it)) return AppPresenceConfigurationException("targets[$index].iosScheme is invalid")
            }
            target.androidPackageName?.let {
                if (!isValidAndroidPackageName(it)) {
                    return AppPresenceConfigurationException("targets[$index].androidPackageName is invalid")
                }
            }
        }
        return null
    }

    /** Lowercases iOS schemes (they compare case-insensitively); order is preserved. */
    fun normalize(configuration: AppPresenceConfiguration): AppPresenceConfiguration =
        configuration.copy(targets = configuration.targets.map { normalize(it) })

    fun normalize(target: AppPresenceTarget): AppPresenceTarget =
        target.copy(iosScheme = target.iosScheme?.lowercase(Locale.ROOT))

    /**
     * SHA-256 (lowercase hex) of the canonical JSON of the targets: normalized, sorted by
     * id (ordinal), each object with its keys in lexicographic order and explicit nulls,
     * no whitespace. `enabled` is not part of it. Example of the hashed text:
     * `[{"androidPackageName":"a.b","id":"x","iosScheme":null}]`.
     */
    fun fingerprint(targets: List<AppPresenceTarget>): String {
        val canonical = targets.map { normalize(it) }.sortedBy { it.id }.joinToString(",", "[", "]") { target ->
            "{\"androidPackageName\":${quoteOrNull(target.androidPackageName)}," +
                "\"id\":${JSONObject.quote(target.id)}," +
                "\"iosScheme\":${quoteOrNull(target.iosScheme)}}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun quoteOrNull(value: String?): String = if (value == null) "null" else JSONObject.quote(value)
}

/**
 * The build evidence the host app generates with `scripts/app-presence-evidence.gradle`:
 * the package names declared in its final manifest `<queries>`.
 */
data class AppPresenceDeclarations(
    val applicationId: String,
    val packages: Set<String>
) {
    fun isDeclared(packageName: String): Boolean = packageName in packages

    companion object {
        /** Asset file name, at the root of the APK `assets/`. */
        const val ASSET_NAME = "bearound_app_presence_declarations.json"
        const val SCHEMA_VERSION = 1

        /**
         * Parses and checks the asset content. Returns `null` (declaration unverified) when
         * the JSON is malformed, `schemaVersion` is not 1, `applicationId` differs from
         * [expectedApplicationId] (the runtime context package name), or `packages` is not a
         * sorted list of unique, syntactically valid package names. Never throws.
         */
        fun parse(json: String?, expectedApplicationId: String): AppPresenceDeclarations? {
            if (json == null) return null
            return try {
                val obj = JSONObject(json)
                val version = obj.opt("schemaVersion")
                if (version !is Int || version != SCHEMA_VERSION) return null
                val applicationId = obj.opt("applicationId") as? String ?: return null
                if (applicationId != expectedApplicationId) return null
                val array = obj.opt("packages") as? JSONArray ?: return null
                val packages = ArrayList<String>(array.length())
                for (index in 0 until array.length()) {
                    val name = array.opt(index) as? String ?: return null
                    if (!AppPresenceValidator.isValidAndroidPackageName(name)) return null
                    packages.add(name)
                }
                if (packages != packages.sorted() || packages.toSet().size != packages.size) return null
                AppPresenceDeclarations(applicationId, packages.toSet())
            } catch (_: JSONException) {
                null
            }
        }
    }
}

internal object AppPresenceJson {
    fun requiredString(json: JSONObject, key: String): String {
        if (!json.has(key) || json.isNull(key)) throw AppPresenceConfigurationException("$key is required")
        return json.opt(key) as? String ?: throw AppPresenceConfigurationException("$key must be a string")
    }

    fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.opt(key) as? String ?: throw AppPresenceConfigurationException("$key must be a string or null")
    }
}
