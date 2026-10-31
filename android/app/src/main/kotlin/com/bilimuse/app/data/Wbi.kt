package com.bilimuse.app.data

/**
 * B 站 WBI 签名。参数按 key 排序、过滤 !'()*、URL 编码后拼上 mixin key 做 MD5。
 */
object Wbi {
    private val MIXIN_KEY_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44,
    )

    fun mixinKey(original: String): String = buildString(32) {
        for (index in MIXIN_KEY_TAB) {
            if (index < original.length) append(original[index]) else append(' ')
        }
    }.take(32)

    fun sign(
        params: Map<String, Any?>,
        imgKey: String,
        subKey: String,
        timestamp: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val mixin = mixinKey(imgKey + subKey)
        val signed = LinkedHashMap<String, String>()
        params.forEach { (key, value) -> signed[key] = value?.toString() ?: "" }
        signed["wts"] = timestamp.toString()
        val query = signed.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(filter(value))}" }
        signed["w_rid"] = md5(query + mixin)
        return signed
    }

    fun filter(value: String): String = value.filterNot { it in "!'()*" }

    /** 与 Python urllib.parse.quote_plus 一致的编码：空格用 +，保留 -_.~。 */
    fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val ch = byte.toInt() and 0xFF
            when {
                ch == ' '.code -> out.append('+')
                ch in 'a'.code..'z'.code ||
                    ch in 'A'.code..'Z'.code ||
                    ch in '0'.code..'9'.code ||
                    ch == '-'.code || ch == '_'.code || ch == '.'.code || ch == '~'.code -> {
                    out.append(ch.toChar())
                }
                else -> {
                    out.append('%')
                    out.append(HEX[ch shr 4].uppercaseChar())
                    out.append(HEX[ch and 0x0F].uppercaseChar())
                }
            }
        }
        return out.toString()
    }

    fun md5(value: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(HEX[value shr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}
