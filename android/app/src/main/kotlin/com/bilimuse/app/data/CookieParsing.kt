package com.bilimuse.app.data

/** 解析 `a=1; b=2` 形式的 Cookie 头；值里允许出现 '='。 */
fun parseCookieHeader(header: String?): Map<String, String> {
    if (header.isNullOrBlank()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (part in header.split(';')) {
        val equals = part.indexOf('=')
        if (equals <= 0) continue
        val name = part.substring(0, equals).trim()
        val value = part.substring(equals + 1).trim()
        if (name.isNotEmpty() && value.isNotEmpty()) result[name] = value
    }
    return result
}
