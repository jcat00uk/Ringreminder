package com.jcat.ringreminder

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.android.billingclient.api.*

class BillingManager(private val context: Context) {

    companion object {
        const val PRODUCT_ID = "ring_reminder_pro"
    }

    private var billingClient: BillingClient? = null

    fun start(onProStatus: (Boolean) -> Unit) {
        val client = BillingClient.newBuilder(context)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    purchases.forEach { handlePurchase(it, onProStatus) }
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        billingClient = client

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases(onProStatus)
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun queryPurchases(onProStatus: (Boolean) -> Unit) {
        val client = billingClient ?: return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val owned = purchases.any {
                    it.products.contains(PRODUCT_ID) &&
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                PrefsHelper(context).hasPurchasedPro = owned
                onProStatus(owned)
                purchases
                    .filter { it.products.contains(PRODUCT_ID) && !it.isAcknowledged &&
                               it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach { acknowledge(it) }
            }
        }
    }

    fun refresh() {
        val client = billingClient ?: return
        if (client.isReady) queryPurchases {}
    }

    fun restorePurchases(onResult: (restored: Boolean) -> Unit) {
        val client = billingClient
        if (client == null || !client.isReady) { onResult(false); return }
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val owned = purchases.any {
                    it.products.contains(PRODUCT_ID) &&
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                if (owned) PrefsHelper(context).hasPurchasedPro = true
                purchases
                    .filter { it.products.contains(PRODUCT_ID) && !it.isAcknowledged &&
                               it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach { acknowledge(it) }
                onResult(owned)
            } else {
                onResult(false)
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity, onProStatus: (Boolean) -> Unit) {
        val client = billingClient
        if (client == null || !client.isReady) {
            activity.runOnUiThread {
                Toast.makeText(activity, "Connecting to Google Play… please try again in a moment.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        ) { result, detailsList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK || detailsList.isEmpty()) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Product not available (code ${result.responseCode}). Check Play Console product is Active.", Toast.LENGTH_LONG).show()
                }
                return@queryProductDetailsAsync
            }
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(detailsList[0])
                        .build()
                ))
                .build()
            activity.runOnUiThread { client.launchBillingFlow(activity, flowParams) }
        }
    }

    private fun handlePurchase(purchase: Purchase, onProStatus: (Boolean) -> Unit) {
        if (purchase.products.contains(PRODUCT_ID) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            PrefsHelper(context).hasPurchasedPro = true
            onProStatus(true)
            acknowledge(purchase)
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        billingClient?.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        ) {}
    }

    val status: String
        get() {
            val client = billingClient ?: return "Not started"
            return if (client.isReady) "Connected" else "Connecting / Disconnected"
        }

    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
    }
}
