package com.jcat.ringreminder

import android.app.Application

class RingReminderApp : Application() {
    val billingManager: BillingManager by lazy { BillingManager(this) }

    override fun onTerminate() {
        super.onTerminate()
        billingManager.destroy()
    }
}
