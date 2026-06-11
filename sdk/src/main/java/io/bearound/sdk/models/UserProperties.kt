package io.bearound.sdk.models

/**
 * User properties that can be associated with beacon data
 */
data class UserProperties(
    val internalId: String? = null,
    val email: String? = null,
    val name: String? = null,
    val customProperties: Map<String, String> = emptyMap()
) {
    fun toDictionary(): Map<String, Any> {
        val dict = mutableMapOf<String, Any>()
        dict.putAll(customProperties)
        
        internalId?.let { dict["internalId"] = it }
        email?.let { dict["email"] = it }
        name?.let { dict["name"] = it }
        
        return dict
    }

    val hasProperties: Boolean
        get() = internalId != null || email != null || name != null || customProperties.isNotEmpty()

    /** Returns a copy with [other]'s non-null fields overriding this one; custom keys are merged. */
    fun mergedWith(other: UserProperties): UserProperties = UserProperties(
        internalId = other.internalId ?: internalId,
        email = other.email ?: email,
        name = other.name ?: name,
        customProperties = customProperties + other.customProperties
    )
}

