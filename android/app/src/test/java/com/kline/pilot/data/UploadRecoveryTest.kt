package com.kline.pilot.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UploadRecoveryTest {
    private val photo = PendingPhoto("photo", "owner", "branch", "delivery", "category", "Clothing / Shirts / Formal",
        "original", "upload.jpg", "hash", 100, 80)
    private val delivery = Delivery("delivery", "owner", "branch", "Test delivery")
    private class FakeGateway : IntakeGateway {
        var accepted = mutableSetOf<String>(); var linkCount = 0; var received = false
        var receipt = ReceiptState(false, false, false); var conflict = false; var loseAck = false
        override fun createDelivery(delivery: Delivery) {}
        override fun upload(photo: PendingPhoto): Boolean {
            accepted.add(photo.id)
            if (loseAck) { loseAck = false; throw IOException("ack lost") }
            return received
        }
        override fun readReceipt(photo: PendingPhoto) = receipt
        override fun linkDelivery(photo: PendingPhoto) {
            linkCount++
            if (conflict) throw CatalogHttpException(409, "arbitrary translated message")
        }
    }
    @Test fun lostAcknowledgementReusesSameIntakeIdentity() {
        val api = FakeGateway().apply { loseAck = true }
        val recovery = UploadRecovery(api)
        assertThrows(IOException::class.java) { recovery.transfer(photo, delivery) { _, _ -> } }
        assertEquals("complete", recovery.transfer(photo, delivery) { _, _ -> }.state)
        assertEquals(setOf(photo.id), api.accepted)
    }
    @Test fun alreadyReceivedSkipsDeliveryMutation() {
        val api = FakeGateway().apply { received = true; receipt = ReceiptState(true, false, false) }
        assertEquals("complete", UploadRecovery(api).transfer(photo, delivery) { _, _ -> }.state)
        assertEquals(0, api.linkCount)
    }
    @Test fun concurrentReceiptResolvesLinkConflictByStateNotEnglishMessage() {
        val api = FakeGateway().apply { conflict = true; receipt = ReceiptState(true, false, false) }
        assertEquals("complete", UploadRecovery(api).transfer(photo, delivery) { _, _ -> }.state)
    }
    @Test fun unrelatedConflictRetainsAttention() {
        val api = FakeGateway().apply { conflict = true }
        assertEquals("attention", UploadRecovery(api).transfer(photo, delivery) { _, _ -> }.state)
    }
    @Test fun legacyReconciliationNeverLooksComplete() {
        val api = FakeGateway().apply { received = true; receipt = ReceiptState(true, true, false) }
        assertEquals("attention", UploadRecovery(api).transfer(photo, delivery) { _, _ -> }.state)
    }
    @Test fun cancellationRetainsAttention() {
        val api = FakeGateway().apply { conflict = true; receipt = ReceiptState(false, false, true) }
        assertEquals("attention", UploadRecovery(api).transfer(photo, delivery) { _, _ -> }.state)
    }
}
