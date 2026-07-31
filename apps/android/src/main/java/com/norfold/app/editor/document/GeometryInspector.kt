package com.norfold.app.editor.document

import com.norfold.app.domain.PageLimits
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject

/**
 * Exact numeric entry for a scene object's geometry — X, Y, W, H and Rotation (roadmap Phase 2 §7).
 *
 * Gesture and inspector are the *same* edit reached two ways, so this deliberately produces a
 * [SceneGeometry] and nothing else: the commit path, the lock rules and the single canonical transaction all
 * stay in [DocumentSceneController]. An inspector that wrote to the document itself would be a second way to
 * change geometry, and the second one is always the one that forgets a lock.
 *
 * **Why parsing lives here and not in a text field.** `"1e40"`, `"NaN"`, `"-0.0"` and `""` all parse to
 * something a `Float` will accept and a layout will not survive. Rejecting them at the boundary — once, in
 * pure JVM, where it is unit-testable — is what keeps a typo from producing a page-sized object at infinity
 * that the user then cannot select in order to fix.
 */
object GeometryInspector {

    /** The five fields the inspector exposes, in the order it presents them. */
    enum class Field(val label: String, val unitSuffix: String) {
        X("X", "pt"),
        Y("Y", "pt"),
        WIDTH("W", "pt"),
        HEIGHT("H", "pt"),
        ROTATION("Rotation", "°"),
        ;

        /** This field's current value for [geometry], as the inspector should display it. */
        fun valueOf(geometry: SceneGeometry): Float = when (this) {
            X -> geometry.x
            Y -> geometry.y
            WIDTH -> geometry.width
            HEIGHT -> geometry.height
            ROTATION -> geometry.rotation
        }

        /** True when this field cannot be edited for [obj] — its own lock says so. */
        fun isLockedFor(obj: SceneObject): Boolean = when (this) {
            X, Y -> !obj.canMove
            WIDTH, HEIGHT -> !obj.canResize
            ROTATION -> !obj.canRotate
        }

        /** Why this field is read-only, or null when it is editable. */
        fun lockReasonFor(obj: SceneObject): String? = when {
            !isLockedFor(obj) -> null
            this == ROTATION && !obj.kind.supportsRotation -> "${obj.kind.name} objects cannot be rotated"
            this == ROTATION -> "This object's size is locked"
            this == X || this == Y -> "This object's position is locked"
            else -> "This object's size is locked"
        }
    }

    /**
     * [text] as a value for [field], or null when it is not a usable number.
     *
     * Null rather than a fallback, deliberately: silently substituting 0 for `"12x"` would move an object to
     * the page origin while the user was still typing, and they would have no way to tell that from an edit
     * they made. A null leaves the field showing what they typed and the object exactly where it was.
     */
    fun parse(field: Field, text: String): Float? {
        val value = text.trim().replace(',', '.').toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        return when (field) {
            Field.WIDTH, Field.HEIGHT -> value.coerceIn(SceneGeometry.MIN_SIZE, PageLimits.MAX_SIDE_PT)
            // Rotation wraps rather than clamping: -90 and 270 are the same angle, and refusing one of them
            // would make a perfectly ordinary way to express a quarter-turn simply not work.
            Field.ROTATION -> ((value % 360f) + 360f) % 360f
            // Position is intentionally unclamped to the page. Content that hangs off the sheet is a real
            // layout (a bleed, a pull-quote) and clamping it here would silently reflow the user's design.
            Field.X, Field.Y -> value.coerceIn(-PageLimits.MAX_SIDE_PT, PageLimits.MAX_SIDE_PT)
        }
    }

    /**
     * [geometry] with [field] set to [value].
     *
     * Applied one field at a time because that is how a user edits: committing all five whenever any one of
     * them changes would make "I nudged the width" also re-write the rotation the user had not touched, with
     * whatever the other field happened to be showing mid-edit.
     */
    fun apply(geometry: SceneGeometry, field: Field, value: Float): SceneGeometry = when (field) {
        Field.X -> geometry.copy(x = value)
        Field.Y -> geometry.copy(y = value)
        Field.WIDTH -> geometry.copy(width = value)
        Field.HEIGHT -> geometry.copy(height = value)
        Field.ROTATION -> geometry.copy(rotation = value)
    }.normalized()

    /**
     * How a field's value is rendered back into its text box.
     *
     * Whole numbers lose their `.0` because most page geometry is whole points and `"595.0"` reads as noise;
     * anything else keeps two decimals, which is finer than a point is worth arguing about and stops a value
     * from appearing to change merely because it was displayed.
     */
    fun display(value: Float): String {
        if (!value.isFinite()) return "0"
        val rounded = Math.round(value * 100f) / 100f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }
}
