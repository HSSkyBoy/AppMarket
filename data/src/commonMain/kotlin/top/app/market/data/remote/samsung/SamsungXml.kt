package top.app.market.data.remote.samsung

/** Small tolerant XML reader for SamsungProtocol's list/value response shape. */
internal data class SamsungXmlNode(
    val name: String,
    val attributes: Map<String, String>,
    val text: String,
    val children: List<SamsungXmlNode>,
) {
    fun child(name: String): SamsungXmlNode? = children.firstOrNull { it.name.equals(name, true) }

    fun descendants(name: String): List<SamsungXmlNode> = buildList {
        fun visit(node: SamsungXmlNode) {
            node.children.forEach { child ->
                if (child.name.equals(name, true)) add(child)
                visit(child)
            }
        }
        visit(this@SamsungXmlNode)
    }

    fun namedValues(): Map<String, String> = buildMap {
        fun visit(node: SamsungXmlNode) {
            node.children.forEach { child ->
                child.attributes["name"]?.let { key ->
                    if (key !in this) put(key, child.text.trim())
                }
                visit(child)
            }
        }
        visit(this@SamsungXmlNode)
    }
}

internal data class SamsungXmlResponse(
    val returnCode: Int,
    val errorCode: String,
    val errorMessage: String,
    val startNum: Int,
    val endNum: Int,
    val endOfList: Boolean,
    val lists: List<Map<String, String>>,
) {
    val isSuccess: Boolean get() = returnCode == 0
}

internal object SamsungXml {
    fun parseResponse(raw: String): SamsungXmlResponse {
        val root = parse(raw)
        val response = if (root.name.equals("response", true)) root else root.child("response")
            ?: root.descendants("response").firstOrNull()
            ?: error("Samsung response is missing the protocol response node")
        val error = response.child("errorInfo")?.child("errorString")
            ?: response.descendants("errorString").firstOrNull()
        val lists = response.children.filter { it.name.equals("list", true) }
        return SamsungXmlResponse(
            returnCode = response.attributes["returnCode"]?.toIntOrNull() ?: -1,
            errorCode = error?.attributes?.get("errorCode").orEmpty(),
            errorMessage = error?.text?.trim().orEmpty(),
            startNum = response.attributes["startNum"]?.toIntOrNull() ?: 0,
            endNum = response.attributes["endNum"]?.toIntOrNull() ?: 0,
            endOfList = response.attributes["endOfList"] == "1",
            lists = lists.map(SamsungXmlNode::namedValues),
        )
    }

    fun parse(raw: String): SamsungXmlNode {
        val document = MutableNode("#document", emptyMap())
        val stack = mutableListOf(document)
        var index = 0
        while (index < raw.length) {
            val lt = raw.indexOf('<', index)
            if (lt < 0) {
                stack.last().text.append(raw.substring(index))
                break
            }
            if (lt > index) stack.last().text.append(raw.substring(index, lt))
            when {
                raw.startsWith("<!--", lt) -> {
                    val end = raw.indexOf("-->", lt + 4)
                    index = if (end < 0) raw.length else end + 3
                }

                raw.startsWith("<![CDATA[", lt) -> {
                    val end = raw.indexOf("]]>", lt + 9)
                    val valueEnd = if (end < 0) raw.length else end
                    stack.last().text.append(raw.substring(lt + 9, valueEnd))
                    index = if (end < 0) raw.length else end + 3
                }

                raw.startsWith("<?", lt) -> {
                    val end = raw.indexOf("?>", lt + 2)
                    index = if (end < 0) raw.length else end + 2
                }

                raw.startsWith("<!", lt) -> {
                    val end = tagEnd(raw, lt + 2)
                    index = if (end < 0) raw.length else end + 1
                }

                raw.startsWith("</", lt) -> {
                    val end = raw.indexOf('>', lt + 2)
                    if (stack.size > 1) stack.removeAt(stack.lastIndex)
                    index = if (end < 0) raw.length else end + 1
                }

                else -> {
                    val end = tagEnd(raw, lt + 1)
                    if (end < 0) break
                    var body = raw.substring(lt + 1, end).trim()
                    val selfClosing = body.endsWith('/')
                    if (selfClosing) body = body.dropLast(1).trimEnd()
                    val nameEnd = body.indexOfFirst(Char::isWhitespace).let { if (it < 0) body.length else it }
                    val rawName = body.substring(0, nameEnd)
                    val node = MutableNode(rawName.substringAfter(':'), parseAttributes(body.substring(nameEnd)))
                    stack.last().children += node
                    if (!selfClosing) stack += node
                    index = end + 1
                }
            }
        }
        val first = document.children.firstOrNull() ?: error("Empty XML document")
        return first.freeze()
    }

    fun escape(value: String): String = buildString(value.length + 16) {
        value.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> char
                }
            )
        }
    }

    private fun tagEnd(raw: String, start: Int): Int {
        var quote: Char? = null
        for (index in start until raw.length) {
            val char = raw[index]
            if (quote == null && (char == '"' || char == '\'')) quote = char
            else if (quote == char) quote = null
            else if (quote == null && char == '>') return index
        }
        return -1
    }

    private fun parseAttributes(raw: String): Map<String, String> = buildMap {
        var index = 0
        while (index < raw.length) {
            while (index < raw.length && raw[index].isWhitespace()) index++
            val keyStart = index
            while (index < raw.length && !raw[index].isWhitespace() && raw[index] != '=') index++
            if (index == keyStart) break
            val key = raw.substring(keyStart, index).substringAfter(':')
            while (index < raw.length && raw[index].isWhitespace()) index++
            if (index >= raw.length || raw[index] != '=') continue
            index++
            while (index < raw.length && raw[index].isWhitespace()) index++
            if (index >= raw.length || (raw[index] != '"' && raw[index] != '\'')) continue
            val quote = raw[index++]
            val valueStart = index
            while (index < raw.length && raw[index] != quote) index++
            put(key, decode(raw.substring(valueStart, index)))
            if (index < raw.length) index++
        }
    }

    private fun decode(raw: String): String {
        var value = raw
        repeat(3) {
            val decoded = value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .let { named ->
                    Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(named) { match ->
                        val token = match.groupValues[1]
                        val number = if (token.startsWith('x', true)) {
                            token.drop(1).toIntOrNull(16)
                        } else {
                            token.toIntOrNull()
                        }
                        number?.takeIf { codePoint -> codePoint in 0..0xffff }
                            ?.toChar()
                            ?.toString()
                            ?: match.value
                    }
                }
            if (decoded == value) return value
            value = decoded
        }
        return value
    }

    private class MutableNode(
        val name: String,
        val attributes: Map<String, String>,
        val text: StringBuilder = StringBuilder(),
        val children: MutableList<MutableNode> = mutableListOf(),
    ) {
        fun freeze(): SamsungXmlNode = SamsungXmlNode(
            name = name,
            attributes = attributes,
            text = decode(text.toString()),
            children = children.map(MutableNode::freeze),
        )
    }
}
