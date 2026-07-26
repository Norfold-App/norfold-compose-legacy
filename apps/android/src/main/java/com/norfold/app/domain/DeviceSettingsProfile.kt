package com.norfold.app.domain

enum class SettingsProfileCategory { Appearance, Editor, Accessibility }

data class DeviceSettingsProfile(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val screenClass: String,
    val schemaVersion: Int = 1,
    val updatedAt: Long,
    val fontScale: Float = 1f,
    val uiScale: Float = 1f,
    val appLabelMode: ControlLabelMode = ControlLabelMode.Adaptive,
    val appIconFamily: AppIconFamily = AppIconFamily.MaterialSymbolsRounded,
    val appFont: String = "Inter",
    val uiDensityCompact: Boolean = false,
    val editorToolbarScale: Float = 1f,
    val editorIconFamily: EditorIconFamily = EditorIconFamily.UseApp,
    val editorLineWidth: EditorLineWidth = EditorLineWidth.Comfortable,
    val editorFontFamily: EditorFontFamily = EditorFontFamily.Sans,
    val reduceMotion: Boolean = false,
) {
    fun applyTo(
        settings: AppSettings,
        categories: Set<SettingsProfileCategory>,
    ): AppSettings {
        var next = settings
        if (SettingsProfileCategory.Appearance in categories) {
            next = next.copy(
                fontScale = fontScale.coerceIn(0.5f, 2f),
                uiScale = uiScale.coerceIn(0.5f, 2f),
                appLabelMode = appLabelMode,
                appIconFamily = appIconFamily,
                appFont = appFont,
                uiDensityCompact = uiDensityCompact,
            )
        }
        if (SettingsProfileCategory.Editor in categories) {
            next = next.copy(
                editorToolbarScale = editorToolbarScale.coerceIn(0.5f, 2f),
                editorIconFamily = editorIconFamily,
                editorLineWidth = editorLineWidth,
                editorFontFamily = editorFontFamily,
            )
        }
        if (SettingsProfileCategory.Accessibility in categories) {
            next = next.copy(reduceMotion = reduceMotion)
        }
        return next
    }

    fun changesFrom(settings: AppSettings): List<DeviceSettingChange> = buildList {
        fun change(category: SettingsProfileCategory, key: String, current: Any, incoming: Any) {
            if (current != incoming) add(DeviceSettingChange(category, key, current.toString(), incoming.toString()))
        }
        change(SettingsProfileCategory.Appearance, "Font scale", settings.fontScale, fontScale)
        change(SettingsProfileCategory.Appearance, "UI scale", settings.uiScale, uiScale)
        change(SettingsProfileCategory.Appearance, "Control labels", settings.appLabelMode, appLabelMode)
        change(SettingsProfileCategory.Appearance, "App icon family", settings.appIconFamily, appIconFamily)
        change(SettingsProfileCategory.Appearance, "App font", settings.appFont, appFont)
        change(SettingsProfileCategory.Appearance, "Compact density", settings.uiDensityCompact, uiDensityCompact)
        change(SettingsProfileCategory.Editor, "Toolbar scale", settings.editorToolbarScale, editorToolbarScale)
        change(SettingsProfileCategory.Editor, "Editor icon family", settings.editorIconFamily, editorIconFamily)
        change(SettingsProfileCategory.Editor, "Editor line width", settings.editorLineWidth, editorLineWidth)
        change(SettingsProfileCategory.Editor, "Editor font", settings.editorFontFamily, editorFontFamily)
        change(SettingsProfileCategory.Accessibility, "Reduce motion", settings.reduceMotion, reduceMotion)
    }
}

data class DeviceSettingChange(
    val category: SettingsProfileCategory,
    val name: String,
    val currentValue: String,
    val incomingValue: String,
)

data class DeviceSettingsImportPreview(
    val source: DeviceSettingsProfile,
    val targetDeviceId: String,
    val changes: List<DeviceSettingChange>,
)

fun AppSettings.toDeviceSettingsProfile(updatedAt: Long = System.currentTimeMillis()) = DeviceSettingsProfile(
    deviceId = settingsDeviceId,
    deviceName = settingsDeviceName,
    deviceType = settingsDeviceType,
    screenClass = settingsScreenClass,
    schemaVersion = settingsSchemaVersion,
    updatedAt = updatedAt,
    fontScale = fontScale,
    uiScale = uiScale,
    appLabelMode = appLabelMode,
    appIconFamily = appIconFamily,
    appFont = appFont,
    uiDensityCompact = uiDensityCompact,
    editorToolbarScale = editorToolbarScale,
    editorIconFamily = editorIconFamily,
    editorLineWidth = editorLineWidth,
    editorFontFamily = editorFontFamily,
    reduceMotion = reduceMotion,
)
