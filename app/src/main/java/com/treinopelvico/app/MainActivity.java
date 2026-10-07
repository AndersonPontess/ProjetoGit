package com.treinopelvico.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 1001;
    private static final int REQ_CREATE_BACKUP = 1002;
    private static final int REQ_OPEN_BACKUP = 1003;

    private WebView webView;
    private String pendingBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NotificationHelper.createChannel(this);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(
                        "if(window.syncNativeState){syncNativeState();}",
                        null
                );
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidNative");
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CREATE_BACKUP &&
                resultCode == RESULT_OK &&
                data != null &&
                data.getData() != null &&
                pendingBackup != null) {
            Uri uri = data.getData();
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Backup salvo.", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Não foi possível salvar o backup.", Toast.LENGTH_LONG).show();
            }
            pendingBackup = null;
        }

        if (requestCode == REQ_OPEN_BACKUP &&
                resultCode == RESULT_OK &&
                data != null &&
                data.getData() != null) {
            Uri uri = data.getData();
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = is.read(chunk)) != -1) {
                        buffer.write(chunk, 0, n);
                    }
                    String json = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                    String quoted = JSONObject.quote(json);
                    webView.evaluateJavascript(
                            "if(window.importBackupFromNative){importBackupFromNative(" +
                                    quoted + ");}",
                            null
                    );
                }
            } catch (Exception e) {
                Toast.makeText(this, "Não foi possível ler o backup.", Toast.LENGTH_LONG).show();
            }
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void syncState(boolean enabled, String time1, String time2,
                              int perDay, boolean discrete, String startDate,
                              String progressDate, int progressCount) {
            runOnUiThread(() -> ReminderScheduler.updateConfig(
                    MainActivity.this,
                    enabled, time1, time2, perDay, discrete,
                    startDate, progressDate, progressCount
            ));
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            REQ_NOTIFICATIONS
                    );
                } else {
                    Toast.makeText(
                            MainActivity.this,
                            "Notificações permitidas.",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            });
        }

        @JavascriptInterface
        public void vibrate(long milliseconds) {
            runOnUiThread(() -> {
                try {
                    Vibrator v;
                    if (Build.VERSION.SDK_INT >= 31) {
                        VibratorManager vm =
                                (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                        v = vm.getDefaultVibrator();
                    } else {
                        v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    }

                    if (v != null && v.hasVibrator()) {
                        long ms = Math.max(20, Math.min(milliseconds, 350));
                        if (Build.VERSION.SDK_INT >= 26) {
                            v.vibrate(VibrationEffect.createOneShot(
                                    ms,
                                    VibrationEffect.DEFAULT_AMPLITUDE
                            ));
                        } else {
                            v.vibrate(ms);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean enabled) {
            runOnUiThread(() -> {
                if (enabled) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void openBackup() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                startActivityForResult(i, REQ_OPEN_BACKUP);
            });
        }

        @JavascriptInterface
        public void saveBackup(String fileName, String json) {
            runOnUiThread(() -> {
                pendingBackup = json;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(
                        Intent.EXTRA_TITLE,
                        fileName == null || fileName.isEmpty()
                                ? "treino-pelvico-backup.json"
                                : fileName
                );
                startActivityForResult(i, REQ_CREATE_BACKUP);
            });
        }
    }
}
