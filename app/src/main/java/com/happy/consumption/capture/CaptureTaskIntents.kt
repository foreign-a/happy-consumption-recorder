package com.happy.consumption.capture

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.happy.consumption.MainActivity

/** Each transient screenshot task starts independently of app and system-settings tasks. */
internal fun gatewayIntent(context: Context, requestId: String, startedAt: Long): Intent =
    Intent(context, CaptureGatewayActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        .putExtra(CAPTURE_REQUEST_ID, requestId)
        .putExtra(CAPTURE_STARTED_AT, startedAt)

internal fun previewIntent(context: Context, requestId: String): Intent =
    Intent(context, CapturePreviewActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        .putExtra(CAPTURE_REQUEST_ID, requestId)

internal fun accessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Preserve any existing app task and its draft while delivering a pending screenshot selection. */
internal fun screenshotMainIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
