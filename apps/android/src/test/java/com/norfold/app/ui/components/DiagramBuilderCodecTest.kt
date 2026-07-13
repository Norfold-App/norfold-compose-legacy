package com.norfold.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramBuilderCodecTest {
    private val fallbackColor = "#6750A4"

    @Test
    fun `flowchart form round trips connections shapes and per node colors`() {
        val input = DiagramBuilderModel(
            kind = DiagramKind.Flowchart,
            direction = FlowDirection.LR,
            nodes = listOf(
                DiagramNodeInput("Start", "Capture", "#112233", DiagramNodeShape.Circle),
                DiagramNodeInput("Choose", "Keep it?", "#445566", DiagramNodeShape.Diamond),
                DiagramNodeInput("Store", "Save", "#778899", DiagramNodeShape.Rounded),
            ),
            edges = listOf(
                DiagramEdgeInput("Start", "Choose", "Review"),
                DiagramEdgeInput("Choose", "Store", "Yes"),
            ),
        )

        val encoded = MermaidDiagramCodec.encode(input, fallbackColor)
        val decoded = MermaidDiagramCodec.decodeOrNull(encoded, fallbackColor)

        assertNotNull(decoded)
        assertEquals(FlowDirection.LR, decoded?.direction)
        assertEquals(input.nodes.map { it.id }, decoded?.nodes?.map { it.id })
        assertEquals(input.nodes.map { it.label }, decoded?.nodes?.map { it.label })
        assertEquals(input.nodes.map { it.shape }, decoded?.nodes?.map { it.shape })
        assertEquals(input.nodes.map { it.color }, decoded?.nodes?.map { it.color })
        assertEquals(input.edges, decoded?.edges)
        assertTrue(encoded.contains("Start((\"Capture\"))"))
        assertTrue(encoded.contains("Choose{\"Keep it?\"}"))
        assertTrue(encoded.contains("Store(\"Save\")"))
        assertTrue(encoded.contains("classDef norfold_Start_0 fill:#112233,stroke:#112233,color:#FFFFFF"))
        assertTrue(encoded.contains("classDef norfold_Choose_1 fill:#445566,stroke:#445566,color:#FFFFFF"))
        assertTrue(encoded.contains("classDef norfold_Store_2 fill:#778899,stroke:#778899,color:#000000"))
    }

    @Test
    fun `existing compact graph source reseeds the visual form`() {
        val source = """graph TD
  A[Start] --> B{Choose}
  B -->|Yes| C((Done))
  style B fill:#ABCDEF,stroke:#ABCDEF"""

        val decoded = MermaidDiagramCodec.decodeOrNull(source, fallbackColor)

        assertEquals(DiagramKind.Flowchart, decoded?.kind)
        assertEquals(listOf("A", "B", "C"), decoded?.nodes?.map { it.id })
        assertEquals(
            listOf(DiagramNodeShape.Rounded, DiagramNodeShape.Diamond, DiagramNodeShape.Circle),
            decoded?.nodes?.map { it.shape },
        )
        assertEquals("#ABCDEF", decoded?.nodes?.first { it.id == "B" }?.color)
        assertEquals("Yes", decoded?.edges?.last()?.label)
        assertTrue(MermaidDiagramCodec.validate(requireNotNull(decoded)).isEmpty())
    }

    @Test
    fun `sequence form round trips participants messages and reply style`() {
        val input = DiagramBuilderModel(
            kind = DiagramKind.Sequence,
            participants = listOf(
                DiagramParticipantInput("Client", "Mobile app"),
                DiagramParticipantInput("Vault", "Encrypted vault"),
            ),
            messages = listOf(
                DiagramMessageInput("Client", "Vault", "Save snapshot"),
                DiagramMessageInput("Vault", "Client", "Stored", dashed = true),
            ),
        )

        val encoded = MermaidDiagramCodec.encode(input, fallbackColor)
        val decoded = MermaidDiagramCodec.decodeOrNull(encoded, fallbackColor)

        assertEquals(DiagramKind.Sequence, decoded?.kind)
        assertEquals(input.participants, decoded?.participants)
        assertEquals(input.messages, decoded?.messages)
        assertTrue(encoded.contains("Client->>Vault: Save snapshot"))
        assertTrue(encoded.contains("Vault-->>Client: Stored"))
    }

    @Test
    fun `legacy semicolon separated diagrams reseed guided forms`() {
        val flowchart = MermaidDiagramCodec.decodeOrNull(
            "graph TD; A[Start] --> B{Choose}; B -->|Yes| C((Done)); style B fill:#ABCDEF,stroke:#ABCDEF;",
            fallbackColor,
        )
        val sequence = MermaidDiagramCodec.decodeOrNull(
            "sequenceDiagram; participant App as Mobile app; participant Vault as Vault; " +
                "App->>Vault: Save; Vault-->>App: Stored;",
            fallbackColor,
        )

        assertEquals(listOf("A", "B", "C"), flowchart?.nodes?.map { it.id })
        assertEquals(DiagramNodeShape.Diamond, flowchart?.nodes?.first { it.id == "B" }?.shape)
        assertEquals("#ABCDEF", flowchart?.nodes?.first { it.id == "B" }?.color)
        assertEquals(listOf("App", "Vault"), sequence?.participants?.map { it.id })
        assertEquals(listOf(false, true), sequence?.messages?.map { it.dashed })
    }

    @Test
    fun `flowchart subgraphs reopen as a safe flattened visual form`() {
        val source = """flowchart TD
  subgraph Capture["Capture lane"]
    Inbox("Inbox") --> Triage{"Keep it?"}
  end
  Triage --> Done(("Done"))
  classDef highlight fill:#336699,stroke:#336699,color:#FFFFFF
  class Triage highlight"""

        val decoded = MermaidDiagramCodec.decodeOrNull(source, fallbackColor)

        assertEquals(DiagramKind.Flowchart, decoded?.kind)
        assertEquals(listOf("Inbox", "Triage", "Done"), decoded?.nodes?.map { it.id })
        assertEquals(
            listOf(DiagramNodeShape.Rounded, DiagramNodeShape.Diamond, DiagramNodeShape.Circle),
            decoded?.nodes?.map { it.shape },
        )
        assertEquals("#336699", decoded?.nodes?.first { it.id == "Triage" }?.color)
        assertEquals(
            listOf(DiagramEdgeInput("Inbox", "Triage"), DiagramEdgeInput("Triage", "Done")),
            decoded?.edges,
        )
        assertTrue(MermaidDiagramCodec.validate(requireNotNull(decoded)).isEmpty())
    }

    @Test
    fun `advanced source detects every offered starter type`() {
        DiagramKind.entries.forEach { kind ->
            val templates = MermaidDiagramCodec.templateCatalog(kind, fallbackColor)

            assertFalse("$kind has no starter", templates.isEmpty())
            templates.forEach { template ->
                assertEquals(kind, MermaidDiagramCodec.detectKind(template.source))
            }
        }
    }

    @Test
    fun `validation rejects duplicate invalid and dangling flowchart ids`() {
        val model = DiagramBuilderModel(
            nodes = listOf(
                DiagramNodeInput("1 bad", "Bad", fallbackColor),
                DiagramNodeInput("Same", "First", fallbackColor),
                DiagramNodeInput("same", "Second", fallbackColor),
            ),
            edges = listOf(DiagramEdgeInput("Same", "Missing")),
        )

        val errors = MermaidDiagramCodec.validate(model)

        assertTrue(errors.any { "must start with a letter" in it })
        assertTrue(errors.any { "used more than once" in it })
        assertTrue(errors.any { "reference existing node IDs" in it })
    }

    @Test
    fun `labels are encoded safely and restored into the form`() {
        val input = DiagramBuilderModel(
            nodes = listOf(
                DiagramNodeInput("A", "Capture \"now\" & review", fallbackColor),
                DiagramNodeInput("B", "Done", fallbackColor),
            ),
            edges = listOf(DiagramEdgeInput("A", "B", "yes | later")),
        )

        val encoded = MermaidDiagramCodec.encode(input, fallbackColor)
        val decoded = MermaidDiagramCodec.decodeOrNull(encoded, fallbackColor)

        assertTrue(encoded.contains("&quot;now&quot; &amp; review"))
        assertTrue(encoded.contains("yes &#124; later"))
        assertEquals(input.nodes.map { it.label }, decoded?.nodes?.map { it.label })
        assertEquals("yes | later", decoded?.edges?.single()?.label)
    }
}
