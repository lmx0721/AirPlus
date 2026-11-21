package net.airplus.api

import com.google.gson.annotations.SerializedName


/**
 * Data classes for the API
 */
data class GitHubRelease(
    @SerializedName("tag_name")
    val tagName: String,
    val name: String,
    val body: String?,
    @SerializedName("html_url")
    val htmlUrl: String,
    @SerializedName("published_at")
    val publishedAt: String,
    val prerelease: Boolean,
    val draft: Boolean
)

/**
 * Settings
 *
 * Settings only stores the setting ID, name, type, description, date, contributors and status
 * The setting id will later be used to actually request the setting and load it
 */
data class AutoSettings(
    @SerializedName("setting_id")
    val settingId: String,
    val name: String,
    @SerializedName("setting_type")
    val type: AutoSettingsType,
    val description: String,
    var date: String,
    val contributors: String,
    @SerializedName("status_type")
    val statusType: AutoSettingsStatusType,
    @SerializedName("status_date")
    var statusDate: String
)

/**
 * Settings type
 *
 * Some might prefer RAGE to LEGIT and vice versa
 * Might add more in the future
 */
enum class AutoSettingsType(val displayName: String) {
    @SerializedName("Rage")
    RAGE("Rage"),

    @SerializedName("Legit")
    LEGIT("Legit")
}

/**
 * Status of the settings will allow you to know whether it is bypassing or not
 */
enum class AutoSettingsStatusType(val displayName: String) {
    @SerializedName("NotBypassing")
    NOT_BYPASSING("Not Bypassing"),

    @SerializedName("Bypassing")
    BYPASSING("Bypassing"),

    @SerializedName("Undetectable")
    UNDETECTABLE("Undetectable"),

    @SerializedName("Unknown")
    UNKNOWN("Unknown")
}
