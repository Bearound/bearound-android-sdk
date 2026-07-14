package io.bearound.sdk.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.bearound.sdk.BeAroundSDK

/**
 * Optional FCM entry point for Bearound **silent-push wake-up** — the Android counterpart of
 * the iOS silent push. The backend sends a data-only, high-priority FCM message (marked
 * `bearound`) to trigger an on-demand scan + sync, even with the app in the background.
 *
 * ## Wiring (only if your app uses Firebase Cloud Messaging)
 *
 * **If you do NOT already have your own `FirebaseMessagingService`**, register THIS one in
 * your app's `AndroidManifest.xml`:
 *
 * ```xml
 * <service
 *     android:name="io.bearound.sdk.push.BearoundMessagingService"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="com.google.firebase.MESSAGING_EVENT" />
 *     </intent-filter>
 * </service>
 * ```
 *
 * **If you ALREADY have your own `FirebaseMessagingService`**, do NOT register this one —
 * forward the message and token to the SDK from yours instead:
 *
 * ```kotlin
 * override fun onMessageReceived(message: RemoteMessage) {
 *     if (BeAroundSDK.getInstance(this).handleRemoteMessage(message.data)) return
 *     // ...your own push handling for non-Bearound messages...
 * }
 * override fun onNewToken(token: String) {
 *     BeAroundSDK.getInstance(this).setPushToken(token)
 * }
 * ```
 *
 * This class is intentionally **not** declared in the SDK's own manifest: `firebase-messaging`
 * is a `compileOnly` dependency, so auto-registering it would crash apps that don't bundle
 * Firebase. It stays inert in the `.aar` until you register it.
 */
class BearoundMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // Bearound wake-up? handle it. This service is Bearound-dedicated; apps with mixed
        // push providers should use their own service + handleRemoteMessage() instead.
        BeAroundSDK.getInstance(applicationContext).handleRemoteMessage(message.data)
    }

    override fun onNewToken(token: String) {
        // Auto-capture the FCM token so the backend can target this device (complements the
        // best-effort fetch in configure()).
        BeAroundSDK.getInstance(applicationContext).setPushToken(token)
    }
}
