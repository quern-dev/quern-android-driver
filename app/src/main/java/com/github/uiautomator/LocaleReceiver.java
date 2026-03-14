package com.github.uiautomator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Broadcast receiver for changing the system locale via ADB.
 *
 * Usage:
 *   adb shell am broadcast -a com.github.uiautomator.SET_LOCALE \
 *     -n com.github.uiautomator/.LocaleReceiver \
 *     --es lang en --es country US
 *
 * Requires CHANGE_CONFIGURATION permission, granted via:
 *   adb shell pm grant com.github.uiautomator android.permission.CHANGE_CONFIGURATION
 *
 * Works on all Android versions through API 32. On API 33+ the only
 * available method (updatePersistentConfiguration) requires WRITE_SETTINGS
 * which cannot be granted via ADB on non-rootable devices. For rootable
 * emulators on API 33+, use setprop instead.
 *
 * Locale change technique adapted from Appium Settings (Apache 2.0).
 */
public class LocaleReceiver extends BroadcastReceiver {
    private static final String TAG = "QuernLocale";
    private static final String ACTION_SET_LOCALE = "com.github.uiautomator.SET_LOCALE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SET_LOCALE.equals(intent.getAction())) {
            return;
        }

        String lang = intent.getStringExtra("lang");
        String country = intent.getStringExtra("country");

        if (lang == null || lang.isEmpty()) {
            Log.e(TAG, "Missing 'lang' extra");
            return;
        }
        if (country == null) {
            country = "";
        }

        Locale locale = country.isEmpty()
                ? new Locale(lang)
                : new Locale(lang, country);

        Log.i(TAG, "Setting locale to " + locale.toLanguageTag());

        try {
            setSystemLocale(locale);
            Log.i(TAG, "Locale changed successfully to " + locale.toLanguageTag());
        } catch (Exception e) {
            Log.e(TAG, "Failed to set locale: " + e.getMessage(), e);
        }
    }

    /**
     * Change the system locale using the hidden IActivityManager API.
     * Requires android.permission.CHANGE_CONFIGURATION.
     *
     * Tries updateConfiguration first (works on API ≤ 32 with just
     * CHANGE_CONFIGURATION), then falls back to updatePersistentConfiguration
     * (API 33+, also needs WRITE_SETTINGS).
     */
    private void setSystemLocale(Locale locale) throws ReflectiveOperationException {
        // Get IActivityManager instance
        Class<?> amnClass = Class.forName("android.app.ActivityManagerNative");
        Method getDefault = amnClass.getMethod("getDefault");
        getDefault.setAccessible(true);
        Object amn = getDefault.invoke(amnClass);

        // Re-resolve the actual class (may be a proxy on newer APIs)
        Class<?> amnActualClass = Class.forName(amn.getClass().getName());

        // Get current configuration
        Method getConfig = amnActualClass.getMethod("getConfiguration");
        getConfig.setAccessible(true);
        Configuration config = (Configuration) getConfig.invoke(amn);

        // Mark as user-initiated locale change
        Field userSetLocale = config.getClass().getField("userSetLocale");
        userSetLocale.setBoolean(config, true);

        // Set the locale
        config.locale = locale;
        config.setLayoutDirection(locale);

        // Try all known method signatures for applying configuration.
        // updateConfiguration(Configuration) works on API ≤ 32 with CHANGE_CONFIGURATION.
        // updatePersistentConfiguration(Configuration) is available on API 33+ but
        // additionally requires WRITE_SETTINGS.
        String[] candidates = {"updateConfiguration", "updatePersistentConfiguration"};
        for (String methodName : candidates) {
            for (Method m : amnActualClass.getMethods()) {
                if (!m.getName().equals(methodName)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length >= 1 && params[0] == Configuration.class) {
                    Log.i(TAG, "Using " + methodName);
                    m.setAccessible(true);
                    m.invoke(amn, config);
                    return;
                }
            }
        }

        throw new NoSuchMethodException(
                "No updateConfiguration or updatePersistentConfiguration method found on "
                + amnActualClass.getName());
    }
}
