package com.norfold.app.editor.selection

/**
 * The selection domains the engine supports (spec §4.6). A domain is the *kind of thing* a selection lives
 * in; every concrete selection target names both its domain and its [SelectionGranularity] within it.
 */
enum class SelectionDomain {
    TEXT,
    FLOW,
    DOCUMENT,
    TABLE,
    CHART,
    RELATIONSHIP,
    APP_ITEM,
}

/**
 * Every selectable granularity across all domains (spec §4.6), each tagged with its owning [domain]. Kept
 * as one flat enum so the engine can reason about mixed-domain selections uniformly, while [domain] keeps
 * each value unambiguously scoped.
 */
enum class SelectionGranularity(val domain: SelectionDomain) {
    // text
    TEXT_CARET(SelectionDomain.TEXT),
    TEXT_CHARACTER_RANGE(SelectionDomain.TEXT),
    TEXT_PARAGRAPH(SelectionDomain.TEXT),
    TEXT_FRAME(SelectionDomain.TEXT),

    // Flow
    FLOW_BLOCK(SelectionDomain.FLOW),
    FLOW_RANGE(SelectionDomain.FLOW),
    FLOW_SUBTREE(SelectionDomain.FLOW),
    FLOW_MULTIPLE_BLOCKS(SelectionDomain.FLOW),

    // Document
    DOC_OBJECT(SelectionDomain.DOCUMENT),
    DOC_MULTIPLE_OBJECTS(SelectionDomain.DOCUMENT),
    DOC_GROUP(SelectionDomain.DOCUMENT),
    DOC_FRAME(SelectionDomain.DOCUMENT),
    DOC_CHILD(SelectionDomain.DOCUMENT),
    DOC_PAGE(SelectionDomain.DOCUMENT),

    // Table
    TABLE_CELL(SelectionDomain.TABLE),
    TABLE_RANGE(SelectionDomain.TABLE),
    TABLE_ROW(SelectionDomain.TABLE),
    TABLE_COLUMN(SelectionDomain.TABLE),
    TABLE_WHOLE(SelectionDomain.TABLE),

    // Chart
    CHART_WHOLE(SelectionDomain.CHART),
    CHART_LAYER(SelectionDomain.CHART),
    CHART_SERIES(SelectionDomain.CHART),
    CHART_MARK(SelectionDomain.CHART),
    CHART_AXIS(SelectionDomain.CHART),
    CHART_LEGEND(SelectionDomain.CHART),
    CHART_ANNOTATION(SelectionDomain.CHART),

    // Relationship
    RELATIONSHIP(SelectionDomain.RELATIONSHIP),
    RELATIONSHIP_ENDPOINT(SelectionDomain.RELATIONSHIP),
    RELATIONSHIP_WAYPOINT(SelectionDomain.RELATIONSHIP),

    // app items
    APP_TASK(SelectionDomain.APP_ITEM),
    APP_EVENT(SelectionDomain.APP_ITEM),
    APP_FILE(SelectionDomain.APP_ITEM),
    APP_DATABASE_ITEM(SelectionDomain.APP_ITEM),
}

/** Lock scope on a target (spec §4.13). A locked target stays selectable/inspectable; ops explain the lock. */
enum class LockScope { NONE, POSITION, SIZE, CONTENT, FULL }
