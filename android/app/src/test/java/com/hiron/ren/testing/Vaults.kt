package com.hiron.ren.testing

import com.hiron.ren.core.Vault
import com.hiron.ren.core.VaultCipher
import com.hiron.ren.core.VaultItem
import com.hiron.ren.core.VaultKind
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a key held in memory, standing in for the Android Keystore, which the JVM tests don't have. */
class MemoryCipher : VaultCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }.doFinal(sealed, 12, sealed.size - 12)
}

/** A vault in a new temporary file. */
fun vault(cipher: MemoryCipher = MemoryCipher(), file: File = Files.createTempDirectory("vault").resolve("vault.bin").toFile()): Vault = Vault(file, cipher)

/** A Chase login and a card. */
val chase: VaultItem = VaultItem("i1", "Chase", VaultKind.Login, listOf("chase.com"), listOf("username" to "tim.h", "password" to "hunter2-secret"))
val visa: VaultItem = VaultItem("i2", "Visa", VaultKind.Card, listOf("shop.example"), listOf("name" to "Tim", "number" to "4242424242424242", "expiry" to "12/29", "cvc" to "123"), ask = false)
