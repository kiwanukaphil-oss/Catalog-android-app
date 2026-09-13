package com.kline.pilot.data

data class UploadOutcome(val state: String, val message: String)

/** Stable-ID intake is retriable; receipt and unrelated membership conflicts require explicit readback. */
class UploadRecovery(private val gateway: IntakeGateway) {
    fun transfer(photo: PendingPhoto, delivery: Delivery, progress: (String, String) -> Unit): UploadOutcome {
        progress("uploading", "Uploading saved photo")
        gateway.createDelivery(delivery)
        val wasReceived = gateway.upload(photo)
        if (wasReceived) return reconcileReceipt(photo)
        progress("linking", "Photo uploaded. Linking delivery")
        try { gateway.linkDelivery(photo) }
        catch (error: CatalogHttpException) {
            if (error.status != 409) throw error
            progress("checking", "Checking the current receipt state")
            val receipt = gateway.readReceipt(photo)
            if (receipt.received || receipt.reconcile || receipt.cancelled) return receiptOutcome(receipt)
            return UploadOutcome("attention", "Delivery conflict. Open this lot on the website to resolve it; your photo is retained.")
        }
        return UploadOutcome("complete", "Uploaded to test delivery. AI review and receiving are separate.")
    }
    private fun reconcileReceipt(photo: PendingPhoto) = receiptOutcome(gateway.readReceipt(photo))
    private fun receiptOutcome(receipt: ReceiptState): UploadOutcome = when {
        receipt.cancelled -> UploadOutcome("attention", "This intake was cancelled. Your original photo is retained.")
        receipt.reconcile -> UploadOutcome("attention", "POS reconciliation is required. Review this lot on the website.")
        receipt.received -> UploadOutcome("complete", "Already received. Saved upload resolved without changing stock or delivery.")
        else -> UploadOutcome("attention", "Receipt state could not be confirmed. Your photo is retained.")
    }
}
