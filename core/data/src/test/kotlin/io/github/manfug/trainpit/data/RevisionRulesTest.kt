package io.github.manfug.trainpit.data

import io.github.manfug.trainpit.model.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class RevisionRulesTest {
    private val client = Profile("client", "Client", Role.CLIENT)
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val device = DeviceKey(client.uid, fingerprint(pair.public.encoded), Base64.getEncoder().encodeToString(pair.public.encoded))
    private val keys = mapOf(RevisionRules.deviceIndex(client.uid, device.deviceId) to device)
    private fun signed(id: String, root: String = "root", parents: List<String> = emptyList(), deleted: Boolean = false, grams: Long = 75000): Entry {
        val entry = Entry(id = id, rootId = root, ownerId = client.uid, subjectId = client.uid, kind = EntryKind.WEIGHT,
            deviceId = device.deviceId, createdAt = 1, parents = parents, deleted = deleted, weight = BodyWeight("2026-01-02", grams))
        return sign(entry)
    }
    private fun sign(entry: Entry): Entry = entry.copy(signature = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private); update(entry.signingBytes()); sign()
    }))
    private fun merge(incoming: List<Entry>, existing: List<Entry> = emptyList(), profile: Profile = client, relationships: List<Relationship> = emptyList()) =
        RevisionRules.validateMerge(incoming, existing.associateBy { it.id }, keys, profile, relationships)

    @Test fun replayIsIdempotentAndNeverResurrectsDeletion() {
        val root = signed("root")
        val deleted = signed("deleted", parents = listOf("root"), deleted = true)
        val committed = merge(listOf(deleted, root))
        assertEquals(listOf(root, deleted), committed)
        assertTrue(merge(listOf(root, deleted), committed).isEmpty())
        assertTrue(activeEntries(committed).isEmpty())
        assertEquals(listOf(deleted), revisionHeads(committed)["root"])
    }

    @Test fun divergentEditAndDeleteRemainConflictedUntilExplicitResolution() {
        val root = signed("root")
        val edit = signed("edit", parents = listOf("root"), grams = 76000)
        val deleted = signed("deleted", parents = listOf("root"), deleted = true)
        val committed = merge(listOf(edit, deleted), listOf(root)) + root
        assertEquals(2, revisionHeads(committed).getValue("root").size)
        assertTrue(activeEntries(committed).isEmpty())
        val resolved = signed("resolved", parents = listOf("edit", "deleted"), deleted = true)
        val final = committed + merge(listOf(resolved), committed)
        assertEquals(listOf(resolved), revisionHeads(final)["root"])
        assertTrue(activeEntries(final).isEmpty())
    }

    @Test fun sameIdDifferentContentsAndCrossRootParentsAreRejected() {
        val root = signed("root")
        rejected { merge(listOf(signed("root", grams = 76000)), listOf(root)) }
        val unrelated = signed("other", root = "other")
        rejected { merge(listOf(signed("bad", parents = listOf("other"))), listOf(root, unrelated)) }
        rejected { merge(listOf(signed("missing", parents = listOf("absent"))), listOf(root)) }
    }

    @Test fun wrongOwnerDeviceRevocationAndTamperingAreRejected() {
        val root = signed("root")
        rejected { merge(listOf(root.copy(weight = BodyWeight("2026-01-02", 80000)))) }
        rejected { merge(listOf(sign(root.copy(ownerId = "intruder", subjectId = "intruder", signature = "")))) }
        rejected { RevisionRules.verify(root, keys.mapValues { it.value.copy(revoked = true) }) }
        rejected { RevisionRules.verify(root, emptyMap()) }
    }

    @Test fun bodyWeightRequiresExplicitActiveConsentAndCannotCrossClients() {
        val root = signed("root")
        val trainer = Profile("trainer", "Trainer", Role.TRAINER)
        val relationship = Relationship("trainer_client", "trainer", "client", "Trainer", "Client", RelationshipStatus.ACTIVE)
        rejected { merge(listOf(root), profile = trainer, relationships = listOf(relationship)) }
        assertEquals(listOf(root), merge(listOf(root), profile = trainer, relationships = listOf(relationship.copy(shareBodyWeight = true))))
        rejected { merge(listOf(root), profile = trainer, relationships = listOf(relationship.copy(status = RelationshipStatus.ENDED, shareBodyWeight = true))) }
        rejected { merge(listOf(root), profile = Profile("other", "Other", Role.CLIENT)) }
    }

    @Test fun selectedArchivesRejectBodyWeightAndMissingAncestry() {
        val root = signed("root")
        val manifest = ArchiveManifest(archiveId = "archive", exporterId = "client", subjectId = "client", selectedSessions = true, entries = listOf(root), devices = listOf(device))
        rejected { RevisionRules.archiveEntries(manifest) }
        rejected { RevisionRules.archiveEntries(manifest.copy(selectedSessions = false, entries = listOf(signed("child", parents = listOf("root"))))) }
    }

    @Test fun historicalSessionMayKeepOpaqueUnavailablePlanReference() {
        val session = sign(Entry(id = "session", rootId = "session", ownerId = "client", subjectId = "client", kind = EntryKind.SESSION,
            deviceId = device.deviceId, createdAt = 1, session = TrainingSession("2026-01-02", "Session", listOf(SetResult("set", "Squat", 5, 50000)), planRevisionId = "revoked-trainer-plan")))
        assertEquals(listOf(session), merge(listOf(session)))
        RevisionRules.archiveEntries(ArchiveManifest(archiveId = "archive", exporterId = "client", subjectId = "client", selectedSessions = true, entries = listOf(session), devices = listOf(device)))
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("The invalid revision was accepted") } catch (_: IllegalArgumentException) { }
    }
}
