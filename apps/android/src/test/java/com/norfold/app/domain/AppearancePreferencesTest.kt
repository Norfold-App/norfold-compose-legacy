package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePreferencesTest {
    private fun settings() = AppSettings(
        themeMode = ThemeMode.System,
        themeProfile = ThemeProfile.Neon,
        accentColor = 0xFF6F36FF,
        backupFolderUri = null,
        vaultLockEnabled = false,
        vaultSecretHash = null,
        settingsDeviceId = "phone-1",
        settingsDeviceName = "Test phone",
        settingsDeviceType = "Android phone",
        settingsScreenClass = "Compact",
    )

    @Test
    fun `device profile applies only selected categories and preserves local identity`() {
        val current = settings()
        val source = DeviceSettingsProfile(
            deviceId = "tablet-2",
            deviceName = "Test tablet",
            deviceType = "Android tablet",
            screenClass = "Medium",
            updatedAt = 42,
            fontScale = 1.4f,
            uiScale = 1.2f,
            appLabelMode = ControlLabelMode.IconsAndText,
            editorToolbarScale = 1.6f,
            editorFontFamily = EditorFontFamily.Serif,
            reduceMotion = true,
        )

        val appearanceOnly = source.applyTo(current, setOf(SettingsProfileCategory.Appearance))

        assertEquals("phone-1", appearanceOnly.settingsDeviceId)
        assertEquals("Test phone", appearanceOnly.settingsDeviceName)
        assertEquals(1.4f, appearanceOnly.fontScale)
        assertEquals(1.2f, appearanceOnly.uiScale)
        assertEquals(ControlLabelMode.IconsAndText, appearanceOnly.appLabelMode)
        assertEquals(1f, appearanceOnly.editorToolbarScale)
        assertEquals(EditorFontFamily.Sans, appearanceOnly.editorFontFamily)
        assertFalse(appearanceOnly.reduceMotion)
    }

    @Test
    fun `device profile diff reports category and before-after values`() {
        val current = settings()
        val source = current.toDeviceSettingsProfile(100).copy(
            fontScale = 1.25f,
            editorToolbarScale = 1.5f,
            reduceMotion = true,
        )

        val changes = source.changesFrom(current)

        assertEquals(3, changes.size)
        assertTrue(changes.any { it.category == SettingsProfileCategory.Appearance && it.name == "Font scale" })
        assertTrue(changes.any { it.category == SettingsProfileCategory.Editor && it.name == "Toolbar scale" })
        assertTrue(changes.any { it.category == SettingsProfileCategory.Accessibility && it.name == "Reduce motion" })
    }

    @Test
    fun `device settings profiles survive encrypted backup codec payload shape`() {
        val profile = settings().toDeviceSettingsProfile(123).copy(
            appLabelMode = ControlLabelMode.Adaptive,
            editorToolbarScale = 1.35f,
        )
        val snapshot = BackupSnapshot(
            deviceSettingsProfiles = listOf(profile),
            notes = emptyList(),
            notebooks = emptyList(),
            tags = emptyList(),
            attachments = emptyList(),
        )

        val restored = BackupCodec.decode(BackupCodec.encode(snapshot)).deviceSettingsProfiles.single()

        assertEquals(profile, restored)
    }

    @Test
    fun `shape block and document toolbar override round trip in structured json`() {
        val shape = ShapeBlock(
            id = "shape-1",
            kind = ShapeKind.Star,
            text = "Launch",
            fillColor = 0xFFF2994A,
            strokeColor = 0xFF202537,
            strokeWidthDp = 3f,
            displayHeightDp = 240f,
            rotationDegrees = 15f,
        )
        val document = BlockDocument(listOf(shape))
        val spec = DocCanvasSpec.a4().copy(toolbarLabelMode = DocumentLabelMode.IconsAndText)
        val layoutPayload = DocLayoutJson.encode(emptyMap(), spec)

        assertEquals(document, BlockDocumentJson.decode(BlockDocumentJson.encode(document)))
        assertEquals(DocumentLabelMode.IconsAndText, DocLayoutJson.decodeCanvas(layoutPayload).toolbarLabelMode)
    }
}
