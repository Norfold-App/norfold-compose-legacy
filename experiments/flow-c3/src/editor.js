// FLOW-03 C3 experiment entry point.
// Builds a single self-contained IIFE exposing window.FlowC3, loaded from local
// Android assets. MIT-only Tiptap/ProseMirror packages; no cloud/pro/CDN.
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'

window.FlowC3 = { Editor, StarterKit }
