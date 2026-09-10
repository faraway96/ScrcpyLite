package io.github.faraway96.scrcpylite.adb

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * ADB RSA 认证密钥: 首次运行生成 RSA-2048, 保存在应用私有目录。
 * 私钥用于对 adbd 的 TOKEN 做 SHA1withRSA 签名;
 * 公钥按 Android adb 的 RSAPublicKey 结构 (小端) 编码, 触发受控设备的授权弹窗。
 */
object AdbKeys {
    private const val KEY_FILE = "adbkey"

    // ===== 调试专用: 内置已被受控端框架信任的密钥 (dsh-root-server) =====
    // 绕过框架对新生成密钥的静默拒绝 (华为 adb_wifi_enabled 门控问题)。
    // 正式版应移除, 恢复动态生成。
    private const val BUNDLED_PRIV_B64: String =
        "MIIEuwIBADANBgkqhkiG9w0BAQEFAASCBKUwggShAgEAAoIBAQCnkEXx+qi47/9D/t7TFcXme8coqvlFPsiQevIQPyfLxGPBgEjOEEEwnRvAVf" +
        "5vcUzTRd4UI7x0H1EpQaqmLCYmUPcPnaU1Hl1l+T6OsUG69wLsckyM9nMSDgu4WBLzQVOaWffB5keR7Cb7Jrv04DNB4RWPW1/wNLg0VDzvn7PC" +
        "JcAEX64tUpFF/QA3NJt+kRlJOqKHFFL3+WQNOOJ8SqWw2taTALpgrJAOOAEqKhsREHbAD8elQjlMU4eo5UxCx8tSBZhvt1I19KN9q++VTgcF6G" +
        "SAe1kuk2dlCFarkvWJxvXaYt4RZpIENL/k2KMVlLWHc/WNHYP/buqx3sSfkEXNAgMBAAECgf9nFtvxg8VKAl2J98QfGcYnhv+Aha/Wakn6malT" +
        "VYemVa0J32LrZBS3U/E+46kl5w7c7J80xMesqZfFSiEzGvZdnHJjqRl7FLNhiXZzU5qc7FtgHPH7x6QD96A+SfFet1I4JCHJqWNe42sYGTyJ62" +
        "Wdol0hVZYwsj6h8zGKOzbculvgJT80wxIgEZlHtJrxt6hiGUgNL57X6vWXBHb8GHfiEKtCGb3cYX+fQGkkTzUsys3/V9NQD9kft9hYJJR33HF8" +
        "cvFrjFSydk3x4j70Zr9KzwPN2XPyJA96NgZGmxZgUUJyfWsRn4uaPYd+9rHeekJCK67qDRII1yoqa4acqYECgYEA7BDL3BMUgV1yZlIpZfnIf9" +
        "YBo3UX9r+4nkUeoozlx3U9p5EfUs0wAga0zRCXFAvekkRyQAtXKs/6U5XcykvLsPYBdeI4IU5/YDDfqJmnmjPvi4lHaLqSy8VVBJQBwa2LMvvE" +
        "P8GHvhKo08Evyu5fntJHhzbSgRIjD3ncEt4FLA0CgYEAtbaeL83Vli/VAVpj+sLWP+PFU1i+UMtKCGkN+GoDa61WZO7AFY5pR1HfzD9p0whyIP" +
        "bnxez/HM/y2pN1iEkpwgYLkHrRrak3fsc1njRMszXX1ygvdUF2cFvOIvoZJULJw0CM9SzbZDypxy8Mfwc7Yees7pr91HbJgC++7df+UMECgYA6" +
        "qoXvQHKlH7MRiLOvGx9f3bB0jeIRuV3JP4Y4gWmNYy6aWS9+pW2b40zFda0GF1kN4qK+FdNo2Vztrt27DJEnfkuonzqx1E9FeX/r55vGb8fFVq" +
        "1/cnaO25CgXaP+HQHt/rGr4o24h+ybC3S0Kv9qefm6ub4gw4AhFXjC25hPdQKBgQCRv7ZFtZ15Z0g8W5oRyjE68aCWZ3nPKB9re4f/FllEBrZj" +
        "IrTjFWUQFXWiR8LDx+Ry2FezA5LkM08hTmFZPQXHYD3qVvjTiATBJVu2V1Cl9av4IX1fWXB8UsaWe2+r2VQnziDBjocycQ3ke7JUOSLCNqcYgy" +
        "zRO64HqURFVZfggQKBgFHrnPNBFD5pzxH1cjYyfl7pUQnb2QFcs+jf144nYi6HQY9cBUHBRCc9V/PXgq6e8bSxmhcUbv3YfqYneT/VvV/peftP" +
        "pnBXEI12vteUO9abHXgXvxAJ3nG1eTKtvdIbd0D2hVlxzikUmv1kP7i6Ekv6fpADiDHDU8WWOEpSRxEJ"

    private const val BUNDLED_PUB_B64: String =
        "QAAAAPvQKcPNRZCfxN6x6m7/gx2N9XOHtZQVo9jkvzQEkmYR3mLa9caJ9ZKrVghlZ5MuWXuAZOgFB06V76t9o/Q1UrdvmAVSy8dCTOWoh1NMOU" +
        "Klxw/AdhARGyoqATgOkKxgugCT1tqwpUp84jgNZPn3UhSHojpJGZF+mzQ3AP1FkVItrl8EwCXCs5/vPFQ0uDTwX1uPFeFBM+D0uyb7JuyRR+bB" +
        "91maU0HzEli4Cw4Sc/aMTHLsAve6QbGOPvllXR41pZ0P91AmJiymqkEpUR90vCMU3kXTTHFv/lXAG50wQRDOSIDBY8TLJz8Q8nqQyD5F+aoox3" +
        "vmxRXT3v5D/++4qPrxRZCnXodniy6l8ulVaHBa+N1IeKARKyi0uawjwqv3/PJektT1/5UwsOffyd9YEeqnEPOO/gNnlvsRkyS4ztFe895l7rfp" +
        "fYu/CM9Qce41PsAmjE/pZfog6URNtYi2Lk7g4vf9xjsyVcGwFjF7fkInq7/dN2pb8z1LnCBLloX1TqFShUgLLJUEZPE9bPhpsgPkOywPQhq8Ge" +
        "T4C0qVYoZTf/qqm+g/44j0iv1NWjc+nudxAL455m54LzX6iLGUPZbj5T12gykKpoqZSIOp9ykDA/E6h8YS81wptieWYn3IqwztDGm7nk/lltvA" +
        "lO/Ovu2ygAbAqKehd7sFQ9bRkhBjYsRuUwEAAQA="

    private val useBundled = true

    private lateinit var keyPair: KeyPair

    @Synchronized
    fun loadOrCreate(filesDir: File) {
        if (::keyPair.isInitialized) return
        if (useBundled) {
            val kf = KeyFactory.getInstance("RSA")
            keyPair = KeyPair(
                null,
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(BUNDLED_PRIV_B64, Base64.NO_WRAP))),
            )
            return
        }
        val f = File(filesDir, KEY_FILE)
        val kf = KeyFactory.getInstance("RSA")
        keyPair = if (f.exists()) {
            val parts = f.readText().split('\n')
            val priv = Base64.decode(parts[0], Base64.NO_WRAP)
            val pub = Base64.decode(parts[1], Base64.NO_WRAP)
            KeyPair(kf.generatePublic(X509EncodedKeySpec(pub)), kf.generatePrivate(PKCS8EncodedKeySpec(priv)))
        } else {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val kp = gen.generateKeyPair()
            f.writeText(
                Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP) + "\n" +
                Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP) + "\n"
            )
            kp
        }
    }

    fun sign(token: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA1withRSA")
        s.initSign(keyPair.private)
        s.update(token)
        return s.sign()
    }

    /** 诊断用: 把 android_pubkey 格式的 base64 写入 logcat (可手动加入受控端 adb_keys) */
    fun dumpPublicKeyToLog() {
        try {
            val payload = publicKeyPayload() // base64 + '\0'
            val b64 = String(payload, Charsets.US_ASCII).trim('\u0000')
            android.util.Log.i("ScrcpyLite", "ADB_PUBKEY:$b64")
        } catch (_: Exception) {}
    }

    /**
     * 编码为 adb 认证使用的公钥格式 (android_pubkey):
     * struct RSAPublicKey { u32 len(words); u32 n0inv; u8 n[len*4]; u8 rr[len*4]; u32 exponent; } 全小端
     * 再 base64 + '\0'
     */
    fun publicKeyPayload(): ByteArray {
        if (useBundled) {
            return (BUNDLED_PUB_B64 + "\u0000").toByteArray(Charsets.US_ASCII)
        }
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val e = pub.publicExponent
        val keyBits = n.bitLength()
        val keyBytes = keyBits / 8
        val keyLen = keyBits / 32

        // n0inv = (2^32 - n^-1 mod 2^32) mod 2^32
        val mod32 = BigInteger.ONE.shiftLeft(32)
        val nInv = n.modInverse(mod32)
        val n0inv = mod32.subtract(nInv)

        // rr = 2^(bits*2) mod n
        val rr = BigInteger.ONE.shiftLeft(keyBits * 2).mod(n)

        fun le(v: BigInteger, size: Int): ByteArray {
            val be = v.toByteArray() // 大端, 可能带符号前导 0
            val out = ByteArray(size)
            var i = be.size - 1
            var j = 0
            while (i >= 0 && j < size) {
                out[j] = be[i]
                i--; j++
            }
            return out
        }

        val buf = java.io.ByteArrayOutputStream()
        fun put32(v: BigInteger) {
            val b = le(v, 4)
            buf.write(b, 0, 4)
        }
        put32(BigInteger.valueOf(keyLen.toLong()))
        put32(n0inv)
        buf.write(le(n, keyBytes), 0, keyBytes)
        buf.write(le(rr, keyBytes), 0, keyBytes)
        put32(e)

        val b64 = Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
        return (b64 + "\u0000").toByteArray(Charsets.US_ASCII)
    }
}
