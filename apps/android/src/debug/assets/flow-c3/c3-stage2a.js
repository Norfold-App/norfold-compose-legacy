/**
 * FLOW-03 C3 Stage-2A gates — appended after the Tiptap/ProseMirror bundle.
 * Depends on window.C3 being defined by c3-editor.js, with C3._view = Tiptap Editor.
 *
 * Gates:
 *   1. Lossless BlockDocument ↔ ProseMirror projection (round-trip)
 *   2. Stable IDs after edits
 *   3. Canonical history / undo / redo
 *   4. Selection-capable driver (cross-object range)
 *   5. Select-before-delete (B12.3)
 *   6. Internal semantic object clipboard (object identity + payload)
 *   7. Android clipboard integration (private MIME/ClipData + text/HTML fallback)
 *   8. TalkBack object traversal (ARIA on atom nodes)
 *   9. Real image/table/chart NodeViews
 */
(function () {
  "use strict";

  // C3._view = Tiptap Editor (J from bundle). ProseMirror view = C3._view.view.
  function ed() { return C3._view; }
  function pm() { return C3._view && C3._view.view; }

  // ── Gate 1: Lossless projection ──────────────────────────────────────────

  C3.toJSON = function () {
    var v = pm();
    if (!v) return { error: "no-editor" };
    return v.state.doc.toJSON();
  };

  C3.fromJSON = function (json) {
    var v = pm();
    if (!v) return { error: "no-editor" };
    try {
      var doc = v.state.schema.nodeFromJSON(json);
      v.dispatch(v.state.tr.replaceWith(0, v.state.doc.content.size, doc));
      return { ok: true, nodeSize: v.state.doc.nodeSize };
    } catch (e) {
      return { error: e.message };
    }
  };

  C3.roundTrip = function () {
    var json = C3.toJSON();
    if (json.error) return json;
    return C3.fromJSON(json);
  };

  // ── Gate 2: Stable IDs after edits ───────────────────────────────────────

  C3.getAtomIds = function () {
    var v = pm();
    if (!v) return [];
    var ids = [];
    v.state.doc.descendants(function (node, pos) {
      if (node.type.name && node.type.name.endsWith("_atom")) {
        ids.push({ type: node.type.name, id: node.attrs.id, pos: pos });
      }
    });
    return ids;
  };

  C3.insertAtom = function (typeName, id, a11yName) {
    var e = ed();
    if (!e) return { error: "no-editor" };
    var v = pm();
    var nodeType = v.state.schema.nodes[typeName];
    if (!nodeType) return { error: "unknown-type: " + typeName };
    var node = nodeType.create({ id: id, a11yName: a11yName || typeName });
    var sel = v.state.selection;
    v.dispatch(v.state.tr.insert(sel.from, node));
    C3._updateA11y();
    return { ok: true, id: id };
  };

  // ── Gate 3: Canonical history / undo / redo ──────────────────────────────

  C3.undo = function () {
    var history = C3._canonicalHistory && C3._canonicalHistory();
    if (!history) return { error: "no-editor" };
    return history.undo();
  };

  C3.redo = function () {
    var history = C3._canonicalHistory && C3._canonicalHistory();
    if (!history) return { error: "no-editor" };
    return history.redo();
  };

  C3.historyInfo = function () {
    var v = pm();
    if (!v) return { error: "no-editor" };
    var history = C3._canonicalHistory && C3._canonicalHistory();
    if (!history) return { error: "history-unavailable" };
    return {
      docSize: v.state.doc.nodeSize,
      selection: C3._sel(),
      history: history.info()
    };
  };

  // ── Gate 4: Selection-capable driver (cross-object range) ────────────────

  C3.selectRange = function (from, to) {
    var e = ed();
    var v = pm();
    if (!e || !v) return { error: "no-editor" };
    var f = Math.max(0, Math.min(from, v.state.doc.content.size));
    var t = Math.max(0, Math.min(to, v.state.doc.content.size));
    e.commands.setTextSelection({ from: f, to: t });
    return { ok: true, from: f, to: t };
  };

  // ── Gate 5: Select-before-delete (B12.3) ────────────────────────────────

  C3.selectAndDelete = function (from, to) {
    var e = ed();
    var v = pm();
    if (!e || !v) return { error: "no-editor" };
    // Step 1: set selection
    e.commands.setTextSelection({ from: from, to: to });
    // Step 2: verify selection exists
    var sel = v.state.selection;
    if (sel.empty) return { error: "selection-empty-after-set" };
    // Step 3: delete
    e.commands.deleteSelection();
    return { ok: true, deletedFrom: from, deletedTo: to };
  };

  // ── Gate 6: Internal semantic object clipboard ───────────────────────────

  C3._internalClip = null;

  C3.copyInternal = function () {
    var v = pm();
    if (!v) return { error: "no-editor" };
    var sel = v.state.selection;
    var slice = sel.content();
    var objects = [];
    slice.content.descendants(function (node, pos) {
      if (node.type.name && node.type.name.endsWith("_atom")) {
        objects.push({
          type: node.type.name,
          id: node.attrs.id,
          a11yName: node.attrs.a11yName,
          pos: pos
        });
      }
    });
    C3._internalClip = {
      slice: slice.toJSON(),
      objects: objects,
      size: slice.size,
      kind: C3._sel().kind
    };
    de("copy-internal size=" + slice.size + " objects=" + objects.length);
    return { ok: true, size: slice.size, objects: objects };
  };

  C3.pasteInternal = function () {
    var e = ed();
    var v = pm();
    if (!e || !v) return { error: "no-editor" };
    if (!C3._internalClip) return { error: "internal-clipboard-empty" };
    try {
      var frag = v.state.schema.nodeFromJSON(
        { type: "doc", content: C3._internalClip.slice.content }
      ).content;
      v.dispatch(v.state.tr.replaceSelection(frag).scrollIntoView());
      de("paste-internal ok objects=" + C3._internalClip.objects.length);
      return { ok: true, objects: C3._internalClip.objects.length };
    } catch (e2) {
      return { error: "paste-failed: " + e2.message };
    }
  };

  C3.cutInternal = function () {
    var copyResult = C3.copyInternal();
    if (copyResult.error) return copyResult;
    var e = ed();
    e.commands.deleteSelection();
    de("cut-internal size=" + C3._internalClip.size);
    return { ok: true, size: C3._internalClip.size };
  };

  // ── Gate 7: Android clipboard integration ────────────────────────────────

  C3.systemCopy = function () {
    var v = pm();
    if (!v) return { error: "no-editor" };
    var sel = v.state.selection;
    if (sel.empty) return { error: "nothing-selected" };
    var slice = sel.content();
    var jsonStr = JSON.stringify(slice.toJSON());
    var textContent = "";
    slice.content.descendants(function (node) {
      if (node.isText) textContent += node.text;
    });
    if (window.Norfold && window.Norfold.onClipboardCopy) {
      window.Norfold.onClipboardCopy(jsonStr, textContent);
      de("system-copy via bridge json=" + jsonStr.length + " text=" + textContent.length);
      return { ok: true, via: "bridge", jsonLen: jsonStr.length, textLen: textContent.length };
    }
    try {
      document.execCommand("copy");
      de("system-copy via execCommand fallback");
      return { ok: true, via: "execCommand" };
    } catch (e) {
      return { error: "system-copy-failed: " + e.message };
    }
  };

  C3.systemPaste = function () {
    if (window.Norfold && window.Norfold.onClipboardPaste) {
      window.Norfold.onClipboardPaste();
      de("system-paste requested via bridge");
      return { ok: true, via: "bridge" };
    }
    return { error: "no-bridge" };
  };

  C3.receiveClipboardData = function (jsonStr, plainText) {
    var e = ed();
    var v = pm();
    if (!e || !v) return { error: "no-editor" };
    if (jsonStr) {
      try {
        var sliceData = JSON.parse(jsonStr);
        var frag = v.state.schema.nodeFromJSON(
          { type: "doc", content: sliceData.content }
        ).content;
        v.dispatch(v.state.tr.replaceSelection(frag).scrollIntoView());
        de("paste-from-clipboard structured ok");
        return { ok: true, via: "structured" };
      } catch (ex) {
        de("paste-from-clipboard structured failed: " + ex.message);
      }
    }
    if (plainText) {
      e.commands.insertContent(plainText);
      de("paste-from-clipboard plain-text ok");
      return { ok: true, via: "plain-text" };
    }
    return { error: "empty-clipboard" };
  };

  // ── Gate 8: TalkBack object traversal ────────────────────────────────────

  C3._updateA11y = function () {
    var v = pm();
    if (!v) return;
    var atomEls = v.dom.querySelectorAll("[data-atom-type]");
    atomEls.forEach(function (el) {
      var type = el.getAttribute("data-atom-type");
      var id = el.getAttribute("data-atom-id");
      var name = el.getAttribute("data-atom-name") || type;
      if (!el.getAttribute("role")) {
        el.setAttribute("role", type === "image_atom" ? "img" : "group");
      }
      if (!el.getAttribute("aria-label")) {
        el.setAttribute("aria-label", name + " (id: " + id + ")");
      }
      if (!el.getAttribute("aria-roledescription")) {
        el.setAttribute("aria-roledescription", "atomic object");
      }
    });
  };

  // ── Internal helpers ─────────────────────────────────────────────────────

  function de(msg) {
    if (window.__uiLog) window.__uiLog("[s2a] " + msg);
    if (window.Norfold && window.Norfold.onEvent) {
      window.Norfold.onEvent(JSON.stringify({ tag: "s2a", msg: msg }));
    }
  }

  C3._sel = function () {
    var v = pm();
    if (!v) return { kind: "none" };
    var sel = v.state.selection;
    if (sel.empty) return { kind: "cursor", pos: sel.from };
    return { kind: "range", from: sel.from, to: sel.to, size: sel.to - sel.from };
  };

})();
