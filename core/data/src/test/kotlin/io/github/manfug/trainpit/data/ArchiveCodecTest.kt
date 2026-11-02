package io.github.manfug.trainpit.data

import io.github.manfug.trainpit.model.*
import org.junit.Assert.*
import org.junit.Test

class ArchiveCodecTest {
    private val password = "a long private passphrase"
    private fun manifest() = ArchiveManifest(archiveId = "archive", exporterId = "client", subjectId = "client", createdAt = 1,
        selectedSessions = false, entries = listOf(Entry(id = "weight", rootId = "weight", ownerId = "client", subjectId = "client",
            kind = EntryKind.WEIGHT, deviceId = "device", createdAt = 1, weight = BodyWeight("2026-01-02", 75000), signature = "signature")), devices = emptyList())

    @Test fun authenticatedRoundTripPreservesOriginalRevisions() {
        val original = manifest()
        val encoded = ArchiveCodec.encode(original, password)
        assertEquals(original, ArchiveCodec.decode(encoded, password))
        assertFalse(encoded.toString(Charsets.ISO_8859_1).contains("75000"))
        assertFalse(ArchiveCodec.encode(original, password).contentEquals(encoded))
    }

    @Test fun wrongPassphraseAndCiphertextCorruptionAreRejected() {
        val encoded = ArchiveCodec.encode(manifest(), password)
        rejected { ArchiveCodec.decode(encoded, "a completely different password") }
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        rejected { ArchiveCodec.decode(encoded, password) }
    }

    @Test fun authenticatedHeaderAndUnknownVersionsAreRejected() {
        val encoded = ArchiveCodec.encode(manifest(), password)
        val saltChanged = encoded.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        rejected { ArchiveCodec.decode(saltChanged, password) }
        val unknown = encoded.copyOf().also { it[7] = '2'.code.toByte() }
        rejected { ArchiveCodec.decode(unknown, password) }
        rejected { ArchiveCodec.decode(encoded.copyOf(40), password) }
        rejected { ArchiveCodec.encode(manifest(), "too short") }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("The invalid archive was accepted") } catch (_: IllegalArgumentException) { }
    }
}
