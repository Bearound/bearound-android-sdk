package io.bearound.sdk.models;

import io.bearound.sdk.BeAroundSDK;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyApiCompatibilityTest {
    // Compiled by both variant Java gates against the published eleven-argument signature.
    private static BeAroundSDK legacyConfigure(BeAroundSDK sdk) {
        return sdk.configure("fixture", ScanPrecision.MEDIUM, MaxQueuedPayloads.MEDIUM,
            "android-native", true, 1200000L, 10000L, 300000L, true, true, true);
    }

    @Test public void publishedConstructorsAndCopyRemainCallable() throws Exception {
        SDKConfiguration original = new SDKConfiguration("fixture", "fixture", ScanPrecision.MEDIUM,
            MaxQueuedPayloads.MEDIUM, "android-native", true, 1200000L, 10000L, 300000L,
            true, true, true);
        SDKConfiguration copied = original.copy("fixture", "fixture", ScanPrecision.HIGH,
            MaxQueuedPayloads.MEDIUM, "android-native", true, 1200000L, 10000L, 300000L,
            true, true, true);
        assertEquals(15000L, copied.getSyncInterval());
        SDKConfiguration.class.getDeclaredConstructor(String.class, String.class, ScanPrecision.class,
            MaxQueuedPayloads.class, String.class, boolean.class, long.class, long.class, long.class,
            boolean.class, boolean.class, boolean.class,
            int.class, kotlin.jvm.internal.DefaultConstructorMarker.class);
        java.lang.reflect.Method oldDefaultCopy = SDKConfiguration.class.getDeclaredMethod("copy$default",
            SDKConfiguration.class, String.class, String.class, ScanPrecision.class, MaxQueuedPayloads.class,
            String.class, boolean.class, long.class, long.class, long.class, boolean.class, boolean.class,
            boolean.class, int.class, Object.class);
        SDKConfiguration defaultCopied = (SDKConfiguration) oldDefaultCopy.invoke(null, original,
            null, null, ScanPrecision.HIGH, null, null, false, 0L, 0L, 0L, false, false, false,
            4095 & ~4, null);
        assertEquals(15000L, defaultCopied.getSyncInterval());
        assertEquals(original.getCollectWifi(), defaultCopied.getCollectWifi());
        DataCollectionPolicy.class.getDeclaredConstructor(boolean.class, boolean.class, boolean.class,
            int.class, kotlin.jvm.internal.DefaultConstructorMarker.class);
        DataCollectionPolicy policy = new DataCollectionPolicy(true, false, false);
        assertFalse(policy.copy(true, false, false).getLocation());
        BeAroundSDK.class.getDeclaredMethod("configure$default", BeAroundSDK.class, String.class,
            ScanPrecision.class, MaxQueuedPayloads.class, String.class, boolean.class, long.class,
            long.class, long.class, boolean.class, boolean.class, boolean.class, int.class, Object.class);
    }
}
