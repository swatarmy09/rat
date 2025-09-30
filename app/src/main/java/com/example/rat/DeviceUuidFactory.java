package com.example.rat;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

public class DeviceUuidFactory {
    private static final String PREFS_FILE = "device_id.xml";
    private static final String PREFS_DEVICE_ID = "device_id";
    private static UUID uuid;

    public static synchronized UUID getDeviceUuid(Context context) {
        if (uuid == null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_FILE, 0);
            String id = prefs.getString(PREFS_DEVICE_ID, null);
            if (id != null) {
                uuid = UUID.fromString(id);
            } else {
                uuid = UUID.randomUUID();
                prefs.edit().putString(PREFS_DEVICE_ID, uuid.toString()).commit();
            }
        }
        return uuid;
    }
}
