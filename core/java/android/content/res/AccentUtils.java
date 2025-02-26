package android.content.res;

import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Color;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

/** @hide */
public class AccentUtils {
    private static final String TAG = "AccentUtils";

    private static ArrayList<String> accentResources = new ArrayList<>(
            Arrays.asList("accent_device_default",
                    "accent_device_default_light",
                    "accent_device_default_dark",
                    "material_pixel_blue_dark",
                    "material_pixel_blue_bright",
                    "gradient_start"));

    private static ArrayList<String> gradientResources = new ArrayList<>(
            Arrays.asList("gradient_end"));

    private static final String ACCENT_COLOR_PROP = "persist.sys.theme.accentcolor";
    private static final String GRADIENT_COLOR_PROP = "persist.sys.theme.gradientcolor";

    static boolean isResourceAccent(String resName) {
        for (String ar : accentResources)
            if (resName.contains(ar))
                return true;
        return false;
    }

    static boolean isResourceGradient(String resName) {
        for (String gr : gradientResources)
            if (resName.contains(gr))
                return true;
        return false;
    }

    public static int getNewAccentColor(int defaultColor) {
        return getAccentColor(defaultColor, ACCENT_COLOR_PROP);
    }

    public static int getNewGradientColor(int defaultColor) {
        return getAccentColor(defaultColor, GRADIENT_COLOR_PROP);
    }

    private static int getAccentColor(int defaultColor, String property) {
        final Context context = ActivityThread.currentApplication();
        boolean randomAccent = Settings.System.getInt(context.getContentResolver(),
                        Settings.System.ENABLE_RANDOM_ACCENT, 0) == 1;
        boolean randomGradient = Settings.System.getInt(context.getContentResolver(),
                        Settings.System.ENABLE_RANDOM_GRADIENT, 0) == 1;
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        if ((cal.get(Calendar.MONTH) == 3 && cal.get(Calendar.DAY_OF_MONTH) == 1) ||
             randomAccent) {
            return ColorUtils.genRandomAccentColor(property == ACCENT_COLOR_PROP);
        }
        if (randomGradient) {
            return ColorUtils.genRandomAccentColor(property == GRADIENT_COLOR_PROP);
        }
        try {
            String colorValue = SystemProperties.get(property, "-1");
            return "-1".equals(colorValue)
                    ? defaultColor
                    : Color.parseColor("#" + colorValue);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set accent: " + e.getMessage() +
                    "\nSetting default: " + defaultColor);
            return defaultColor;
        }
    }
}
