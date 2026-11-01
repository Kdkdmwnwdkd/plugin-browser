package com.pluginbrowser.net

import java.security.KeyStore
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 只信「服务器自己发下来的」那张证书链。
 *
 * Android 的系统信任库里还留着好几家 CA 的中间证书副本，构建证书路径时系统会优先
 * 从自己的库里挑，而不是用服务器在握手里真正发下来的那张。中间证书一换代，系统里
 * 那张旧的就会顶上：[CertificateExpiredException] —— 而服务器实际发的那张还有半年。
 *
 * 2026-10-11 起腾讯交互翻译（transmart.qq.com）就是这么挂的：服务端发的是
 * DigiCert 2022 CA1（2032 年到期），系统里那本同名旧证书 10 月 11 日到期，
 * 于是每一台安卓机都判定「证书过期」。桌面浏览器不受影响，因为它们的信任库没有那份旧副本。
 *
 * 做法：先按系统默认校验；失败了，再用「服务器下发的链 + 系统根证书」重验一次。
 * 全程仍然要求一路验到受信根，不做任何跳过——只是不再允许系统拿自己的中间证书替换。
 */
object ChainTrust {

    @Volatile
    private var factory: SSLSocketFactory? = null

    /** 系统默认的 TrustManager，反复 new 一次要几十毫秒，握手时会成倍放大 */
    private val systemTrust: X509TrustManager by lazy { systemTrustManager() }

    fun applyTo(conn: HttpsURLConnection) {
        runCatching { conn.sslSocketFactory = socketFactory() }
    }

    private fun socketFactory(): SSLSocketFactory = factory ?: synchronized(this) {
        factory ?: build().also { factory = it }
    }

    private fun build(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<X509TrustManager>(RelaxedChainTrustManager(systemTrust)), null)
        return context.socketFactory
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        // null 表示使用系统默认库
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("系统没有可用的 X509TrustManager")
    }

    private class RelaxedChainTrustManager(
        private val delegate: X509TrustManager,
    ) : X509TrustManager {

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                delegate.checkServerTrusted(chain, authType)
                return
            } catch (first: CertificateException) {
                // 系统那条路走不通，只看服务器发的链。这条路再失败就把两条路的
                // 原因都带上：真实环境里最常见的还是「手机时间不对」和「证书确实过期」，
                // 报错信息里必须能看出是哪一种
                try {
                    validateUsingServerChainOnly(chain)
                } catch (second: CertificateException) {
                    second.addSuppressed(first)
                    throw second
                }
            }
        }
    }

    /**
     * 用 PKIX 自建路径，证书池里**只有**服务器发来的这几张。
     *
     * 不往 CertStore 里塞系统库，是这个方法存在的全部意义：
     * 一旦让 Builder 去系统库找中间证书，它又会挑到那张过期的旧副本。
     */
    private fun validateUsingServerChainOnly(chain: Array<X509Certificate>) {
        if (chain.isEmpty()) throw CertificateException("服务器没有提供证书")
        val anchors = systemTrust.acceptedIssuers
            .map { TrustAnchor(it, null) }
            .toSet()
        if (anchors.isEmpty()) throw CertificateException("系统没有受信根证书")

        // 服务器可能把顺序打乱（少见但合法），逐张当叶子试一遍
        var last: CertificateException? = null
        for (leaf in chain) {
            try {
                val params = PKIXBuilderParameters(anchors, X509CertSelector().apply {
                    certificate = leaf
                }).apply {
                    isRevocationEnabled = false
                    addCertStore(
                        CertStore.getInstance(
                            "Collection",
                            CollectionCertStoreParameters(chain.toList()),
                        ),
                    )
                }
                CertPathBuilder.getInstance("PKIX").build(params)
                return
            } catch (e: Exception) {
                last = CertificateException("证书链校验失败：${e.message}", e)
            }
        }
        throw last ?: CertificateException("证书链校验失败")
    }
}
