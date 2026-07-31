package com.norfold.app.ui.fonts

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.norfold.app.domain.style.FontFamilyRecord
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.FontManifestCodec
import com.norfold.app.domain.style.FontRegistry
import com.norfold.app.domain.style.FontRequest
import com.norfold.app.domain.style.FontResolution
import com.norfold.app.domain.style.FontSource
import com.norfold.app.domain.style.GuaranteedFonts
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the bundled font library from APK assets (roadmap Phase 3 §15, §18, §22, §30).
 *
 * This is the object that makes "guaranteed offline" true. The previous delivery path built every
 * [FontFamily] from `androidx.compose.ui.text.googlefonts`, which fetches binaries through Play services at
 * first use — §15 is explicit that runtime fetch "is NOT an acceptable delivery mechanism for these 30".
 * Everything here reads from [AssetManager] instead, so a family resolves identically on a device that has
 * never had a network interface: there is no code path from this object to a socket.
 *
 * **Why families are built lazily and cached.** §30 forbids reloading bundled binaries unnecessarily, and
 * a naive implementation reloads constantly — every recomposition that calls `resolve(name)` would build a
 * fresh [FontFamily], and Compose treats an unequal family as a new font to load. So a family is
 * constructed once per (identity, slant) and held; [FontFamily] itself is a lightweight descriptor, and the
 * actual typeface caching below it belongs to Compose's own font cache, which is where it should be.
 *
 * The cache is bounded ([MAX_CACHED_FAMILIES]) because imported and downloaded families are unbounded in
 * principle: a user with 400 imported fonts browsing the picker must not accumulate 400 live descriptors.
 */
object OfflineFontLibrary {

    /** Beyond this, the least-recently-built descriptors are dropped. Bundled families are never evicted. */
    private const val MAX_CACHED_FAMILIES = 96

    @Volatile
    private var assets: AssetManager? = null

    @Volatile
    private var loaded: FontRegistry = FontRegistry(GuaranteedFonts.SYSTEM_FAMILIES)

    /** Imported/downloaded families installed in app-private storage, keyed by [FontIdentity.encode]. */
    private val installed = ConcurrentHashMap<String, FontFamilyRecord>()

    private val families = ConcurrentHashMap<String, FontFamily>()
    private val recency = ArrayDeque<String>()

    /**
     * Reads the packaged manifest. Idempotent and cheap to call again; safe to call off the main thread.
     *
     * A missing or unreadable manifest leaves the registry holding only the system generics rather than
     * throwing. That is deliberate: a packaging mistake must degrade to "the device's own fonts" and be
     * caught by the packaging test, not crash the editor on launch. [isPackaged] reports which happened.
     */
    fun initialize(context: Context) {
        if (assets != null && loaded.builtIns.isNotEmpty()) return
        val manager = context.applicationContext.assets
        val manifest = runCatching {
            manager.open(FontManifestCodec.MANIFEST_ASSET).use { it.readBytes().decodeToString() }
        }.getOrNull()
        val bundled = manifest?.let(FontManifestCodec::decode).orEmpty()
        synchronized(this) {
            assets = manager
            rebuild(bundled)
        }
    }

    /** True once the bundled manifest has been read and describes at least one family. */
    val isPackaged: Boolean get() = loaded.builtIns.isNotEmpty()

    val registry: FontRegistry get() = loaded

    /**
     * Registers a family whose files live in app-private storage rather than in assets.
     *
     * Imported fonts (§21) and downloaded ones (§18) both arrive this way. Their [FontFaceRecord.asset] is
     * an absolute file path, which is how [fontFor] knows to load from disk instead of the APK.
     */
    fun install(record: FontFamilyRecord) {
        synchronized(this) {
            installed[record.identity.encode()] = record
            rebuild(loaded.builtIns)
        }
    }

    /** Forgets an installed family. Documents referencing it resolve to Missing/Substituted, never corrupt. */
    fun uninstall(identity: FontIdentity) {
        synchronized(this) {
            if (installed.remove(identity.encode()) == null) return
            families.keys.removeAll { it.startsWith("${identity.encode()}|") }
            rebuild(loaded.builtIns)
        }
    }

    private fun rebuild(bundled: List<FontFamilyRecord>) {
        loaded = FontRegistry(bundled + installed.values.sortedBy { it.name } + GuaranteedFonts.SYSTEM_FAMILIES)
    }

    // ── Resolution ───────────────────────────────────────────────────────────────────────────────

    /**
     * Resolves a request to a renderable family, honouring script coverage for [text].
     *
     * The [FontResolution] is returned alongside the family so the caller can *say* what happened — the
     * substitution is a fact the UI is allowed to surface (§22) while the document keeps the requested
     * identity untouched.
     */
    fun resolve(request: FontRequest, text: String = ""): LoadedFont {
        val resolution = loaded.resolve(request, text)
        val family = resolution.resolved?.let { fontFor(it, request.italic) }
        return LoadedFont(resolution, family ?: genericFor(request.identity))
    }

    /**
     * A [FontFamily] for [identity], or null when nothing is packaged for it.
     *
     * [italicNeeded] selects which faces to include rather than which single face to load, because Compose
     * needs both slants present in the family to synthesize or select correctly when a span turns italic
     * mid-paragraph.
     */
    fun fontFor(identity: FontIdentity, italicNeeded: Boolean = false): FontFamily? {
        if (identity.source == FontSource.SYSTEM) return systemFamily(identity.id)
        val record = loaded.find(identity) ?: return null
        val key = "${identity.encode()}|${if (italicNeeded) "i" else "u"}"
        families[key]?.let { return it }
        val built = build(record) ?: return null
        remember(key, identity.source)
        families[key] = built
        return built
    }

    /** Legacy display-name entry point, kept so pre-Phase-3 documents keep their fonts (§26). */
    fun fontForLegacyName(name: String?): FontFamily? =
        loaded.resolveLegacyName(name)?.let { fontFor(it) }

    private fun build(record: FontFamilyRecord): FontFamily? {
        val manager = assets
        val fonts = record.faces.flatMap { face ->
            when {
                // A variable face covers the whole axis, so it is registered once per offered weight with
                // the axis pinned. Registering it only at 400 would make bold fall back to synthetic
                // emboldening while the real bold instance sat unused in the file.
                face.variable -> FontFamilyRecord.VARIABLE_WEIGHTS.map { weight ->
                    faceFont(manager, face.asset, weight, face.italic, variable = true)
                }
                else -> listOf(faceFont(manager, face.asset, face.weight, face.italic, variable = false))
            }
        }.filterNotNull()
        return fonts.takeIf { it.isNotEmpty() }?.let(::FontFamily)
    }

    private fun faceFont(
        manager: AssetManager?,
        asset: String,
        weight: Int,
        italic: Boolean,
        variable: Boolean,
    ): Font? {
        if (asset.isBlank()) return null
        val fontWeight = FontWeight(weight.coerceIn(100, 900))
        val style = if (italic) FontStyle.Italic else FontStyle.Normal
        val variation = if (variable) {
            FontVariation.Settings(FontVariation.weight(weight.coerceIn(100, 900)))
        } else {
            FontVariation.Settings()
        }
        return runCatching {
            // An absolute path means an installed (imported/downloaded) file; anything else is an asset.
            if (asset.startsWith('/')) {
                Font(java.io.File(asset), weight = fontWeight, style = style, variationSettings = variation)
            } else {
                val manager1 = manager ?: return null
                Font(
                    path = "${FontManifestCodec.ASSET_ROOT}/$asset",
                    assetManager = manager1,
                    weight = fontWeight,
                    style = style,
                    variationSettings = variation,
                )
            }
        }.getOrNull()
    }

    private fun remember(key: String, source: FontSource) {
        if (source == FontSource.BUILT_IN) return
        synchronized(recency) {
            recency.removeAll { it == key }
            recency.addLast(key)
            while (recency.size > MAX_CACHED_FAMILIES) {
                families.remove(recency.removeFirst())
            }
        }
    }

    private fun systemFamily(id: String): FontFamily = when (id) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }

    /** What to render when nothing is packaged: a generic of the same flavour, never an empty family. */
    private fun genericFor(identity: FontIdentity): FontFamily = when {
        identity.id.contains("mono", ignoreCase = true) ||
            identity.id.contains("code", ignoreCase = true) -> FontFamily.Monospace
        identity.id.contains("serif", ignoreCase = true) -> FontFamily.Serif
        else -> FontFamily.SansSerif
    }

    /** Test/diagnostic hook: how many families are currently held as descriptors. */
    fun cachedFamilyCount(): Int = families.size
}

/** A resolution paired with the family that will actually render it. */
data class LoadedFont(val resolution: FontResolution, val family: FontFamily) {
    val substituted: Boolean get() = resolution.substitution != null
}
