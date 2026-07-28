package com.norfold.app.editor.command

/**
 * A core capability a command may target (spec §4.3). Commands declare the capabilities they require
 * instead of brittle object-type lists, so eligibility is driven by what the current selection *can do*,
 * not by what kind of thing it is. A capability set is offered by a selection/target and matched against
 * each command's [EditorCommand.requiredCapabilities].
 */
enum class Capability {
    SELECTION,
    MOVEMENT,
    RESIZE,
    ROTATION,
    FILL,
    STROKE,
    TEXT_EDITING,
    CROP,
    ORDERING,
    GROUPING,
    FRAMING,
    LINKING,
    COMMENTS,
    DATA_BINDING,
    CHART_MARK_EDITING,
    TABLE_STRUCTURE,
    LOCKING,
    DUPLICATION,
    CONVERSION,
    EXPORT,
}
