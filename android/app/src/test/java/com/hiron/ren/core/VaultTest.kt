package com.hiron.ren.core

import com.hiron.ren.testing.MemoryCipher
import com.hiron.ren.testing.chase
import com.hiron.ren.testing.vault
import com.hiron.ren.testing.visa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class VaultTest {
    @Test
    fun anItemGoesOnlyToHttpsAddressesOnItsSites() {
        assertTrue(chase.allows("https://chase.com/login"))
        assertTrue(chase.allows("https://secure.chase.com/"))
        assertFalse(chase.allows("http://chase.com/login"))
        assertFalse(chase.allows("https://notchase.com/"))
        assertFalse(chase.allows("https://chase.com.evil.example/"))
        assertFalse(chase.allows("not an address"))
    }

    @Test
    fun sitesAreKeptAsDomains() {
        assertEquals("chase.com", VaultItem.site(" https://Chase.com/login?x=1 "))
        assertEquals("api.github.com", VaultItem.site("api.github.com"))
        assertEquals("github.com", VaultItem.site("*.github.com"))
        assertNull(VaultItem.site("localhost"))
        assertNull(VaultItem.site(""))
    }

    @Test
    fun itemsAreSealedAndOpenAgain() {
        val cipher = MemoryCipher()
        val file = Files.createTempDirectory("vault").resolve("vault.bin").toFile()
        vault(cipher, file).apply { save(chase); save(visa) }
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("hunter2"))
        val again = vault(cipher, file)
        assertEquals(listOf(chase, visa), again.all())
        assertEquals(chase, again.find("chase"))
        again.allow(chase.id)
        assertFalse(vault(cipher, file).find("Chase")!!.ask)
        again.delete(visa.id)
        assertEquals(listOf("Chase"), vault(cipher, file).all().map { it.name })
    }

    @Test
    fun theListingHasNamesSitesAndFieldsButNoValues() {
        val v = vault().apply { save(chase) }
        val item = v.listing().getJSONArray("items").getJSONObject(0)
        assertEquals(setOf("name", "kind", "sites", "fields", "asks_first"), item.keys().asSequence().toSet())
        assertEquals("Chase", item.getString("name"))
        assertEquals("""["chase.com"]""", item.getJSONArray("sites").toString())
        assertEquals("""["username","password"]""", item.getJSONArray("fields").toString())
        assertFalse(v.listing().toString().contains("hunter2"))
    }

    @Test
    fun scrubTakesOutEveryValueAsPagesWriteIt() {
        val v = vault().apply { save(chase); save(visa) }
        assertEquals(
            "pw [vault: Chase › password], card [vault: Visa › number] and [vault: Visa › number], cvc 123",
            v.scrub("pw hunter2-secret, card 4242 4242 4242 4242 and 4242-4242-4242-4242, cvc 123"),
        )
    }

    @Test
    fun anEmptyVaultNeverOpensTheCipher() {
        val never = object : VaultCipher {
            override fun seal(plain: ByteArray): ByteArray = error("sealed")
            override fun open(sealed: ByteArray): ByteArray = error("opened")
        }
        assertEquals("text", Vault(Files.createTempDirectory("vault").resolve("vault.bin").toFile(), never).scrub("text"))
    }
}
