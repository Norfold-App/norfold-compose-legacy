# Ably Base Setup

Use **Ably Pub/Sub**, not Ably Spaces as a native Android dependency. The Spaces ideas are useful references, but Norfold should keep provider-independent semantics and use the native Pub/Sub Kotlin/Java SDK.

Potential later namespace split:

- `norfold-live:<artifact-id>` — ephemeral previews
- `norfold-presence:<artifact-id>` — coarse presence if separate

After the event schema is stable, evaluate Ably server-side **conflation** for latest-state preview events. Never conflate durable operations.
