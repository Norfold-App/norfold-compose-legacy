package com.norfold.app.editor.interchange

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

data class PackageEntry(val name: String, val bytes: ByteArray)

data class BoundedPackage(
    val entries: Map<String, PackageEntry>,
    val entryNames: Set<String>,
    val entryCount: Int,
    val expandedBytes: Long,
) {
    operator fun get(path: String): ByteArray? = entries[path]?.bytes
    fun contains(path: String): Boolean = path in entries
    fun names(): Set<String> = entryNames
}

object BoundedPackageReader {
    fun read(
        source: ByteArray,
        limits: ImportSecurityLimits,
        keep: (String) -> Boolean = { true },
    ): BoundedPackage {
        if (source.size.toLong() > limits.maxSourceBytes) {
            throw ImportSecurityException("Source exceeds the ${limits.maxSourceBytes}-byte limit")
        }
        if (source.size < 4 || source[0] != 0x50.toByte() || source[1] != 0x4b.toByte()) {
            throw ImportSecurityException("The source is not an Office ZIP package")
        }
        val retained = linkedMapOf<String, PackageEntry>()
        val seen = linkedSetOf<String>()
        var count = 0
        var expanded = 0L
        try {
            ZipInputStream(ByteArrayInputStream(source)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > limits.maxZipEntries) {
                        throw ImportSecurityException("ZIP entry count exceeds ${limits.maxZipEntries}")
                    }
                    val name = safeEntryName(entry.name)
                    if (!seen.add(name)) throw ImportSecurityException("Duplicate ZIP entry '$name'")
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (entry.size > limits.maxEntryBytes) {
                        throw ImportSecurityException("ZIP entry '$name' exceeds the per-entry limit")
                    }
                    val output = if (keep(name)) ByteArrayOutputStream() else null
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        expanded += read
                        if (entryBytes > limits.maxEntryBytes) {
                            throw ImportSecurityException("ZIP entry '$name' expands beyond the per-entry limit")
                        }
                        if (expanded > limits.maxExpandedBytes) {
                            throw ImportSecurityException("ZIP package expands beyond the total limit")
                        }
                        output?.write(buffer, 0, read)
                    }
                    output?.let { retained[name] = PackageEntry(name, it.toByteArray()) }
                    zip.closeEntry()
                }
            }
        } catch (e: ImportSecurityException) {
            throw e
        } catch (e: ZipException) {
            throw ImportSecurityException("Malformed ZIP package: ${e.message ?: "invalid archive"}")
        } catch (e: Exception) {
            throw ImportSecurityException("Unable to read ZIP package: ${e.message ?: "invalid archive"}")
        }
        return BoundedPackage(retained, seen, count, expanded)
    }

    private fun safeEntryName(raw: String): String {
        val normalized = raw.replace('\\', '/')
        val segments = normalized.split('/')
        if (normalized.isBlank() || normalized.startsWith('/') || ':' in normalized ||
            segments.any { it == ".." || it == "." || it.isBlank() }
        ) {
            throw ImportSecurityException("Unsafe ZIP entry path '$raw'")
        }
        return segments.joinToString("/")
    }
}

data class XmlNode(
    val name: String,
    val attributes: Map<String, String>,
    val children: MutableList<XmlNode> = mutableListOf(),
    private val textBuffer: StringBuilder = StringBuilder(),
) {
    val text: String get() = textBuffer.toString()
    internal fun appendText(value: CharArray, start: Int, length: Int) { textBuffer.append(value, start, length) }
    fun attribute(localName: String): String? = attributes[localName]
        ?: attributes.entries.firstOrNull { it.key.substringAfter(':') == localName }?.value
    fun children(localName: String): List<XmlNode> = children.filter { it.localName == localName }
    fun child(localName: String): XmlNode? = children.firstOrNull { it.localName == localName }
    val localName: String get() = name.substringAfter(':')
    fun descendants(localName: String): Sequence<XmlNode> = sequence {
        for (child in children) {
            if (child.localName == localName) yield(child)
            yieldAll(child.descendants(localName))
        }
    }
    fun allText(): String = buildString {
        append(text)
        children.forEach { append(it.allText()) }
    }
}

data class ParsedXml(val root: XmlNode, val nodeCount: Int)

object SecureXml {
    fun parse(bytes: ByteArray, limits: ImportSecurityLimits): ParsedXml {
        val prefix = bytes.copyOfRange(0, minOf(bytes.size, 4_096)).toString(Charsets.UTF_8).uppercase(Locale.ROOT)
        if ("<!DOCTYPE" in prefix || "<!ENTITY" in prefix) {
            throw ImportSecurityException("DTD and entity declarations are not allowed")
        }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        val stack = ArrayDeque<XmlNode>()
        var root: XmlNode? = null
        var nodeCount = 0
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

            override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
                if (stack.size + 1 > limits.maxXmlDepth) throw SAXException("XML nesting exceeds ${limits.maxXmlDepth}")
                nodeCount++
                if (nodeCount > limits.maxXmlNodes) throw SAXException("XML node count exceeds ${limits.maxXmlNodes}")
                val map = linkedMapOf<String, String>()
                for (index in 0 until attrs.length) {
                    val key = attrs.getQName(index).ifBlank { attrs.getLocalName(index) }
                    map[key] = attrs.getValue(index)
                    attrs.getLocalName(index).takeIf(String::isNotBlank)?.let { map.putIfAbsent(it, attrs.getValue(index)) }
                }
                val node = XmlNode(qName?.takeIf(String::isNotBlank) ?: localName.orEmpty(), map)
                stack.lastOrNull()?.children?.add(node) ?: run { root = node }
                stack.addLast(node)
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                val node = stack.lastOrNull() ?: return
                if (node.text.length + length > limits.maxTextNodeChars) {
                    throw SAXException("XML text node exceeds ${limits.maxTextNodeChars} characters")
                }
                node.appendText(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (stack.isEmpty()) throw SAXException("Malformed XML element stack")
                stack.removeLast()
            }
        }
        try {
            val parser = factory.newSAXParser()
            parser.xmlReader.entityResolver = handler
            parser.parse(ByteArrayInputStream(bytes), handler)
        } catch (e: ImportSecurityException) {
            throw e
        } catch (e: Exception) {
            throw ImportSecurityException("Unsafe or malformed XML: ${e.message ?: "parse failure"}")
        }
        return ParsedXml(root ?: throw ImportSecurityException("XML document has no root element"), nodeCount)
    }
}
