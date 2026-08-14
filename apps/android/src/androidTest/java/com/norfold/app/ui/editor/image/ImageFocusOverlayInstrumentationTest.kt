package com.norfold.app.ui.editor.image

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.norfold.app.domain.ImageBlock
import com.norfold.app.editor.image.ImageFocusCommitHost
import com.norfold.app.editor.image.ImageFocusRuntime
import com.norfold.app.editor.image.ImageMarkup
import com.norfold.app.editor.image.ImageMarkupLabel
import com.norfold.app.editor.image.ImageMarkupLabelKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose seam checks for the image-stage actions; visual pixels are covered by emulator evidence. */
@RunWith(AndroidJUnit4::class)
class ImageFocusOverlayInstrumentationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cropStage_exposesAccessibleMoveAndResizeActionsWithoutInset() {
        val runtime = ImageFocusRuntime(ImageBlock(source = "source"), ImageFocusCommitHost { _, _ -> true })
        compose.setContent {
            var revision by remember { mutableIntStateOf(0) }
            ImageCropOverlay(
                document = runtime.document().also { revision }, runtime = runtime, disabled = false,
                onChanged = { revision++ }, modifier = Modifier.size(300.dp),
            )
        }
        compose.onNodeWithContentDescription("Crop image")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
    }

    @Test
    fun markupStage_exposesSelectAndKeepsTextObjectInRuntime() {
        val runtime = ImageFocusRuntime(ImageBlock(source = "source"), ImageFocusCommitHost { _, _ -> true })
        runtime.edit { it.copy(markup = ImageMarkup(labels = listOf(
            ImageMarkupLabel(id = "text", kind = ImageMarkupLabelKind.TEXT, text = "Editable"),
        ))) }
        compose.setContent {
            var revision by remember { mutableIntStateOf(0) }
            ImageMarkupOverlay(
                document = runtime.document().also { revision }, runtime = runtime, disabled = false,
                tool = ImageMarkupTool.SELECT, selectedLabelIndex = 0, onChanged = { revision++ },
                modifier = Modifier.size(300.dp),
            )
        }
        compose.onNodeWithContentDescription("Select or move annotation").assertIsDisplayed()
        assertEquals("Editable", runtime.document().markup.labels.single().text)
    }

    @Test
    fun freeDistortionStage_exposesFourCornerEditingDescription() {
        val runtime = ImageFocusRuntime(ImageBlock(source = "source"), ImageFocusCommitHost { _, _ -> true })
        compose.setContent {
            var revision by remember { mutableIntStateOf(0) }
            ImageCropOverlay(
                document = runtime.document().also { revision }, runtime = runtime, disabled = false,
                freeTransformEnabled = true, onChanged = { revision++ }, modifier = Modifier.size(300.dp),
            )
        }
        compose.onNodeWithContentDescription("Free distortion. Drag one of four image corners.").assertIsDisplayed()
    }
}
