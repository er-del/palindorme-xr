package com.example.xr.companion

import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enforces LAN/USB-only addressing and pairing-code-derived authenticated encryption
 * (HMAC-SHA256 mutual auth + AES-256-GCM transport security).
 */
object CompanionNetworkSecurity {

    private val random = SecureRandom()

    fun isValidPairingCode(code: String): Boolean {
        val trimmed = code.trim()
        return trimmed.length == 6 && trimmed.all { it.isDigit() }
    }

    /**
     * Returns true for loopback (USB adb reverse / local test), RFC1918 private LAN IPs,
     * link-local addresses, or mDNS `.local` hostnames.
     */
    fun isPrivateOrUsbHost(host: String): Boolean {
        val clean = host.trim().lowercase()
        if (clean.isEmpty()) return false
        if (clean == "localhost" || clean.endsWith(".local")) return true

        val parsed = parseIpv4Literal(clean) ?: return runCatching {
            val addr = InetAddress.getByName(clean)
            isPrivateInetAddress(addr)
        }.getOrDefault(false)

        return isPrivateIpv4Bytes(parsed)
    }

    fun isPrivateInetAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
        if (addr is Inet4Address) {
            return isPrivateIpv4Bytes(addr.address)
        }
        return false
    }

    private fun parseIpv4Literal(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            bytes[i] = v.toByte()
        }
        return bytes
    }

    private fun isPrivateIpv4Bytes(b: ByteArray): Boolean {
        if (b.size != 4) return false
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        return when {
            a0 == 127 -> true // 127.0.0.0/8 USB adb reverse / loopback
            a0 == 10 -> true // 10.0.0.0/8
            a0 == 172 && a1 in 16..31 -> true // 172.16.0.0/12
            a0 == 192 && a1 == 168 -> true // 192.168.0.0/16
            a0 == 169 && a1 == 254 -> true // 169.254.0.0/16 link-local / USB tethering
            a0 == 100 && a1 in 64..127 -> false // CGNAT rejected by default unless user overrides
            else -> false
        }
    }

    fun generateNonceHex(numBytes: Int = 16): String {
        val buf = ByteArray(numBytes)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    fun deriveSessionKey(
        pairingCode: String,
        clientNonce: String,
        serverNonce: String
    ): ByteArray {
        val material = "PALINDORME-XR-LAN-PSK-V1:$pairingCode:$clientNonce:$serverNonce"
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    }

    fun computeAuthProof(sessionKey: ByteArray, transcript: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        val tag = mac.doFinal(transcript.toByteArray(Charsets.UTF_8))
        return tag.joinToString("") { "%02x".format(it) }
    }

    fun encryptAesGcm(sessionKey: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(12)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return out
    }

    fun decryptAesGcm(sessionKey: ByteArray, packet: ByteArray, aad: ByteArray? = null): ByteArray? {
        if (packet.size <= 28) return null // 12B IV + 16B tag minimum
        return runCatching {
            val iv = packet.copyOfRange(0, 12)
            val ct = packet.copyOfRange(12, packet.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
            if (aad != null) cipher.updateAAD(aad)
            cipher.doFinal(ct)
        }.getOrNull()
    }
}
