package com.norfold.app.collaboration.merge

/** Single production selection point; experimental adapters are never selected implicitly. */
object CollaborationMergeEngineFactory {
    val selectedEngineId: String = "norfold-semantic-opset-v1"

    fun create(): CollaborationMergeEngine = SemanticOpSetMergeEngine()
}
