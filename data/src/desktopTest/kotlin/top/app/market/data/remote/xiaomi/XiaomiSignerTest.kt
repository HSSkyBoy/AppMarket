package top.app.market.data.remote.xiaomi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 签名算法黄金用例。锁的是逆向自官方客户端的既有行为，算错时本地无异常、服务端静默拒绝。 */
class XiaomiSignerTest {

    private fun arranged(url: String, nonce: String = "1700000000000_1"): String {
        val method = XiaomiSigner::class.java.getDeclaredMethod(
            "arrange",
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        return method.invoke(XiaomiSigner, url, nonce) as String
    }

    @Test
    fun `值段被逐字符反转且首个等号变和号`() {
        val result = arranged("https://a.b/c?keyword=abc")
        assertTrue(result.contains("keyword&cba"), result)
    }

    @Test
    fun `值内的等号同样变和号并各自反转其后片段`() {
        val result = arranged("https://a.b/c?keyword=a=bc")
        assertTrue(result.contains("keyword&a&cb"), result)
    }

    @Test
    fun `空值参数保留分隔符`() {
        val result = arranged("https://a.b/c?keyword=")
        assertTrue(result.contains("keyword&"), result)
    }

    @Test
    fun `代理对被拆开反转而不是整体保序`() {
        // U+1F600 = D83D DE00，逐 char 反转成 DE00 D83D 两个孤立代理
        val emoji = "😀"
        val result = arranged("https://a.b/c?keyword=${emoji}a")
        val expected = "keyword&a\uDE00\uD83D"
        assertTrue(result.contains(expected), "expected surrogates swapped, got: $result")
    }

    @Test
    fun `未签名的键不进入排列结果`() {
        val result = arranged("https://a.b/c?notSigned=xyz&keyword=abc")
        assertTrue(result.contains("keyword&cba"), result)
        assertTrue(!result.contains("xyz"), result)
    }

    @Test
    fun `同一输入的签名稳定`() {
        val url = "https://a.b/c?keyword=abc&versionCode=123"
        assertEquals(arranged(url), arranged(url))
    }
}
