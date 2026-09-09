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

    private lateinit var keyPair: KeyPair

    @Synchronized
    fun loadOrCreate(filesDir: File) {
        if (::keyPair.isInitialized) return
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
