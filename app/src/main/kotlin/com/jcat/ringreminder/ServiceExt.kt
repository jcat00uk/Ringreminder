package com.jcat.ringreminder

import android.content.Context
import android.content.Intent
import android.os.Build

fun Context.startRingerService() {
    val intent = Intent(this, RingerMonitorService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
    else startService(intent)
}
