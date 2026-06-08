package com.termuxbackground;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WebAppInterface {

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_API_PACKAGE = "com.termux.api";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_CONFIG_DIR = TERMUX_HOME + "/.termux";
    private static final String BACKGROUND_NAME = "background.png";
    private static final String TAG = "TermuxBG";

    private final Context context;
    private final ContentResolver contentResolver;
    private final PackageManager packageManager;
    private final WebView webView;

    private Uri lastImageUri;

    // ── root helpers ──────────────────────────────────────────────

    /** Run a shell command as root via {@code su -c}. */
    private boolean execRoot(String command) {
        Log.d(TAG, "SU exec: " + command);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int exitCode = p.waitFor();
            Log.d(TAG, "SU exit=" + exitCode + " for: " + command);
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "execRoot failed: " + command, e);
            return false;
        }
    }

    /** Check whether {@code su} binary is available. */
    private boolean checkSu() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo ok"});
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "checkSu failed", e);
            return false;
        }
    }

    /** Read file content from {@code path} using root. */
    private String readFileRoot(String path) {
        Log.d(TAG, "SU read: " + path);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            p.waitFor();
            String result = p.exitValue() == 0 ? sb.toString() : "";
            Log.d(TAG, "SU read result: " + (result.isEmpty() ? "(empty)" : result.length() + " chars"));
            return result;
        } catch (Exception e) {
            Log.e(TAG, "readFileRoot failed: " + path, e);
            return "";
        }
    }

    /** Write {@code content} to {@code path} as root, then fix ownership to Termux. */
    private boolean writeFileRoot(String path, String content) {
        Log.d(TAG, "SU write: " + path + " (" + content.length() + " chars)");
        try {
            // Write to a temp file in app cache (no root needed)
            File tempFile = new File(context.getCacheDir(), "termux_props_tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }

            String tempPath = tempFile.getAbsolutePath();

            // Copy temp file to destination via su (same proven method as image copy)
            boolean ok = execRoot("cp " + tempPath + " " + path);
            if (ok) {
                execRoot("chown $(stat -c '%u:%g' " + TERMUX_HOME + ") " + path);
            }
            tempFile.delete();
            Log.d(TAG, "SU write result=" + ok + " for: " + path);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "writeFileRoot failed: " + path, e);
            return false;
        }
    }

    public WebAppInterface(Context context, WebView webView) {
        this.context = context;
        this.contentResolver = context.getContentResolver();
        this.packageManager = context.getPackageManager();
        this.webView = webView;
    }

    public void setLastImageUri(Uri uri) {
        this.lastImageUri = uri;
    }

    @JavascriptInterface
    public String getStatus() {
        try {
            JSONObject status = buildStatus();
            return status.toString();
        } catch (Exception e) {
            return buildError("Failed to build status: " + e.getMessage()).toString();
        }
    }

    @JavascriptInterface
    public String applyBackground(String payloadJson) {
        JSONObject result = new JSONObject();
        Log.d(TAG, "=== applyBackground start ===");
        try {
            JSONObject payload = new JSONObject(payloadJson == null ? "{}" : payloadJson);
            Uri imageUri = resolveImageUri(payload.optString("imageUri", null));
            String opacityStr = payload.optString("opacity", "");
            String animation = payload.optString("animation", "none");
            boolean blur = payload.optBoolean("blur", false);

            Log.d(TAG, "payload: uri=" + imageUri + " opacity=" + opacityStr + " anim=" + animation + " blur=" + blur);

            Status status = parseStatus();
            if (!status.canRunCommands) {
                Log.w(TAG, "blocked: termux not installed");
                return buildBlocked("Termux is not installed. Please install Termux to continue.").toString();
            }

            if (imageUri == null) {
                Log.w(TAG, "blocked: no imageUri");
                return buildError("Select an image before applying.").toString();
            }

            double opacity = parseOpacity(opacityStr);
            if (Double.isNaN(opacity)) {
                return buildError("Invalid opacity value.").toString();
            }

            if (!validateAnimation(animation)) {
                return buildError("Invalid animation option.").toString();
            }

            String mimeType = contentResolver.getType(imageUri);
            Log.d(TAG, "image mimeType=" + mimeType);
            if (!isSupportedMime(mimeType)) {
                return buildError("Unsupported image type. Use PNG or JPEG.").toString();
            }

            // 1. Copy image to app cache
            File cacheDir = context.getCacheDir();
            File cacheImage = new File(cacheDir, BACKGROUND_NAME);
            Log.d(TAG, "step1: copy to cache -> " + cacheImage.getAbsolutePath());
            try (InputStream in = contentResolver.openInputStream(imageUri);
                 OutputStream out = new FileOutputStream(cacheImage)) {
                if (in == null) throw new IOException("Unable to open selected file.");
                byte[] buf = new byte[8 * 1024];
                int len, total = 0;
                while ((len = in.read(buf)) != -1) { out.write(buf, 0, len); total += len; }
                Log.d(TAG, "step1: copied " + total + " bytes to cache");
            }

            // 2. Read existing properties & merge
            Log.d(TAG, "step2: read existing props from Termux");
            String existingProps = readFileRoot(TERMUX_CONFIG_DIR + "/termux.properties");
            Log.d(TAG, "step2: existing props length=" + existingProps.length());
            String mergedProps = mergePropertiesContent(existingProps, opacity, blur, animation);
            Log.d(TAG, "step2: merged props length=" + mergedProps.length());

            // 3. Write via root
            Log.d(TAG, "step3: mkdir " + TERMUX_CONFIG_DIR);
            if (!execRoot("mkdir -p " + TERMUX_CONFIG_DIR)) {
                Log.e(TAG, "step3 FAIL: mkdir");
                return buildError("Unable to create Termux config directory (root).").toString();
            }

            Log.d(TAG, "step4: cp image to Termux");
            if (!execRoot("cp " + cacheImage.getAbsolutePath() + " " + TERMUX_CONFIG_DIR + "/" + BACKGROUND_NAME)) {
                Log.e(TAG, "step4 FAIL: cp image");
                return buildError("Unable to copy background image to Termux.").toString();
            }

            Log.d(TAG, "step5: write properties");
            if (!writeFileRoot(TERMUX_CONFIG_DIR + "/termux.properties", mergedProps)) {
                Log.e(TAG, "step5 FAIL: write props");
                return buildError("Unable to write termux.properties.").toString();
            }

            Log.d(TAG, "step6: fix ownership");
            if (!execRoot("OWNER=$(stat -c '%u:%g' " + TERMUX_HOME + "); chown -R $OWNER " + TERMUX_CONFIG_DIR)) {
                Log.e(TAG, "step6 FAIL: chown");
                return buildError("Unable to fix file ownership.").toString();
            }

            cacheImage.delete();
            Log.d(TAG, "=== applyBackground SUCCESS ===");

            result.put("ok", true);
            result.put("blocked", false);
            result.put("message", "Background applied. Restart Termux to see changes.");
            return result.toString();
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
            return buildError("Invalid payload: " + e.getMessage()).toString();
        } catch (IOException e) {
            Log.e(TAG, "IO error", e);
            return buildError("Failed to write files: " + e.getMessage()).toString();
        }
    }

    @JavascriptInterface
    public String resetBackground() {
        try {
            Status status = parseStatus();
            if (!status.canRunCommands) {
                return buildBlocked("Termux is not installed. Please install Termux to continue.").toString();
            }

            // 1. Read existing properties & strip background keys
            String existingProps = readFileRoot(TERMUX_CONFIG_DIR + "/termux.properties");
            String filteredProps = clearBackgroundKeysContent(existingProps);

            // 2. Write cleaned properties & delete image via root
            if (!writeFileRoot(TERMUX_CONFIG_DIR + "/termux.properties", filteredProps)) {
                return buildError("Unable to write termux.properties.").toString();
            }
            execRoot("rm -f " + TERMUX_CONFIG_DIR + "/" + BACKGROUND_NAME);

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("blocked", false);
            result.put("message", "Background reset. Restart Termux to see changes.");
            return result.toString();
        } catch (Exception e) {
            return buildError("Failed to reset: " + e.getMessage()).toString();
        }
    }

    @JavascriptInterface
    public void openTermuxApiInstallHelp() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wiki.termux.com/wiki/Termux:API"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private Status parseStatus() {
        Status status = new Status();
        status.termuxInstalled = isPackageInstalled(TERMUX_PACKAGE);
        status.termuxApiInstalled = isPackageInstalled(TERMUX_API_PACKAGE);
        status.canRunCommands = status.termuxInstalled;
        if (!status.termuxInstalled) {
            status.lastError = "Termux not installed";
        }
        return status;
    }

    private JSONObject buildStatus() throws JSONException {
        Status status = parseStatus();
        JSONObject statusJson = new JSONObject();
        statusJson.put("termuxInstalled", status.termuxInstalled);
        statusJson.put("termuxApiInstalled", status.termuxApiInstalled);
        statusJson.put("canRunCommands", status.canRunCommands);
        statusJson.put("lastError", status.lastError != null ? status.lastError : "");
        statusJson.put("suAvailable", checkSu());
        statusJson.put("appVersion", BuildConfig.VERSION_NAME);
        return statusJson;
    }

    private boolean validateAnimation(String animation) {
        return TextUtils.equals(animation, "none") || TextUtils.equals(animation, "scroll");
    }

    private boolean isSupportedMime(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.equals("image/png") || mimeType.equals("image/jpeg");
    }

    private double parseOpacity(String opacityStr) {
        try {
            double value = Double.parseDouble(opacityStr);
            if (value >= 0.0 && value <= 1.0) {
                return value;
            }
            return Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private Uri resolveImageUri(String uriFromPayload) {
        if (!TextUtils.isEmpty(uriFromPayload)) {
            return Uri.parse(uriFromPayload);
        }
        return lastImageUri;
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            packageManager.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ── properties helpers (operate on String content) ────────────

    private String mergePropertiesContent(String existingContent, double opacity, boolean blur, String animation) {
        Map<String, String> desired = new HashMap<>();
        desired.put("background", BACKGROUND_NAME);
        desired.put("background.opacity", String.valueOf(opacity));
        desired.put("background.blur", String.valueOf(blur));
        desired.put("background.animation", animation);

        String[] lines = existingContent.isEmpty() ? new String[0] : existingContent.split("\n");

        Set<String> handledKeys = new HashSet<>();
        List<String> updated = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            boolean replaced = false;
            for (String key : desired.keySet()) {
                if (trimmed.startsWith(key + "=")) {
                    updated.add(key + "=" + desired.get(key));
                    handledKeys.add(key);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                updated.add(line);
            }
        }

        List<String> missingLines = new ArrayList<>();
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            if (!handledKeys.contains(entry.getKey())) {
                missingLines.add(entry.getKey() + "=" + entry.getValue());
            }
        }

        if (!missingLines.isEmpty()) {
            if (!updated.isEmpty()) {
                updated.add("");
            }
            updated.add("# Termux Background");
            updated.addAll(missingLines);
        }

        return String.join("\n", updated);
    }

    private String clearBackgroundKeysContent(String existingContent) {
        if (existingContent.isEmpty()) return "";
        List<String> filtered = new ArrayList<>();
        for (String line : existingContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("background=") ||
                trimmed.startsWith("background.opacity=") ||
                trimmed.startsWith("background.blur=") ||
                trimmed.startsWith("background.animation=")) {
                continue;
            }
            filtered.add(line);
        }
        return String.join("\n", filtered);
    }

    private JSONObject buildError(String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", false);
            obj.put("blocked", false);
            obj.put("message", message);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    private JSONObject buildBlocked(String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", false);
            obj.put("blocked", true);
            obj.put("message", message);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    private static class Status {
        boolean termuxInstalled;
        boolean termuxApiInstalled;
        boolean canRunCommands;
        String lastError;
    }
}
