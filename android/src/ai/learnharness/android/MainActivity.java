package ai.learnharness.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 1, SAVE_FILE = 2, AUDIO = 3;
    private static final int MAX_DOWNLOAD = 50 * 1024 * 1024;
    private LocalRelay relay;
    private WebView web;
    private TextView status;
    private LinearLayout root;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest microphone;
    private boolean preparingDownload;
    private File pendingDownload() { return new File(getCacheDir(), "pending-download.bin"); }
    private final java.util.concurrent.ExecutorService tasks = Executors.newCachedThreadPool();
    private int generation;
    private boolean pageFailed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        showPairing();
        String saved = getPreferences(MODE_PRIVATE).getString("pairing", "");
        if (!saved.isEmpty()) connect(saved);
    }
    private TextView text(String value) { TextView view = new TextView(this); view.setText(value); view.setTextSize(17); return view; }
    private Button button(String title, View.OnClickListener listener) { Button b = new Button(this); b.setText(title); b.setOnClickListener(listener); return b; }
    private void base() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setFitsSystemWindows(true);
        root.setPadding(12, 12, 12, 12); root.setBackgroundColor(0xfffafafa); setContentView(root);
    }
    private void showPairing() {
        generation++;
        if (web != null) { web.destroy(); web = null; }
        if (relay != null) { relay.close(); relay = null; }
        base();
        TextView title = text("LearnHarness"); title.setTextSize(28); root.addView(title);
        root.addView(text("Connect to your PC. Paste the pairing string generated on your PC. Keep the PC server running while you learn."));
        EditText pairing = new EditText(this); pairing.setHint("Pairing string"); pairing.setMinLines(3);
        pairing.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pairing.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(pairing);
        root.addView(button("Connect", view -> connect(pairing.getText().toString().trim())));
        status = text(""); root.addView(status);
    }
    private void connect(String value) {
        final int attempt = ++generation;
        status.setText("Connecting to PC…");
        tasks.execute(() -> {
            try {
                Pairing config = new Pairing(value); config.check();
                runOnUiThread(() -> {
                    if (isFinishing() || attempt != generation) return;
                    try {
                        if (relay != null) relay.close();
                        relay = new LocalRelay(config);
                        getPreferences(MODE_PRIVATE).edit().putString("pairing", value).apply();
                        showApp();
                    } catch (Exception e) { status.setText("Could not open the app: " + e.getMessage()); }
                });
            } catch (Exception e) { runOnUiThread(() -> { if (attempt == generation) status.setText("Could not connect. Check the PC address, server, and Wi-Fi. " + e.getMessage()); }); }
        });
    }
    @SuppressWarnings("SetJavaScriptEnabled") private void showApp() {
        base();
        LinearLayout toolbar = new LinearLayout(this);
        status = text("Connected to PC"); status.setTextSize(13);
        toolbar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button("Retry", view -> { status.setText("Reconnecting…"); web.reload(); }));
        toolbar.addView(button("Connection", view -> new AlertDialog.Builder(this).setTitle("PC connection")
            .setMessage("Change or remove the saved connection? Learning data stays on your PC.")
            .setPositiveButton("Change", (dialog, which) -> showPairing())
            .setNeutralButton("Forget", (dialog, which) -> { getPreferences(MODE_PRIVATE).edit().remove("pairing").apply(); CookieManager.getInstance().removeAllCookies(null); showPairing(); })
            .setNegativeButton("Cancel", null).show()));
        root.addView(toolbar);
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUserAgentString(settings.getUserAgentString() + " LearnHarnessAndroid/1");
        settings.setSupportMultipleWindows(true);
        // Debugging is opt-in through adb for local testing; shipped builds default off.
        WebView.setWebContentsDebuggingEnabled(getIntent().getBooleanExtra("webviewDebug", false));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        CookieManager.getInstance().setCookie(relay.origin, "lh_android=" + relay.secret + "; Path=/; HttpOnly; SameSite=Strict");
        CookieManager.getInstance().flush();
        web.addJavascriptInterface(new DownloadBridge(), "LearnHarnessDownloads");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (relay.owns(request.getUrl().toString())) return false;
                if (request.isForMainFrame()) openExternal(request.getUrl());
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) { pageFailed = false; status.setText("Loading…"); }
            @Override public void onPageFinished(WebView view, String url) {
                if (!relay.owns(url)) return;
                if (!pageFailed) status.setText("Connected to PC");
                // Capture blob downloads before the existing browser helpers revoke their URLs.
                // The bridge capability stays inside this top-frame closure, not on window.
                String script = "(()=>{if(window.__lhDownloads)return;window.__lhDownloads=true;document.addEventListener('click',async e=>{const a=e.target.closest?.('a[download]');if(!a)return;const u=new URL(a.href,location.href);if(!['blob:','data:'].includes(u.protocol)&&u.origin!==location.origin)return;e.preventDefault();try{const r=await fetch(a.href);if(!r.ok)throw Error('Download rejected');const b=await r.blob();if(b.size>" + MAX_DOWNLOAD + ")throw Error('File exceeds 50 MiB');const f=new FileReader();f.onload=()=>LearnHarnessDownloads.save(" + JSONObject.quote(relay.secret) + ",a.download||'download',b.type||'application/octet-stream',String(f.result).split(',')[1]);f.readAsDataURL(b)}catch(err){alert('Download failed: '+err.message)}},true)})()";
                view.evaluateJavascript(script, null);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) { pageFailed = true; status.setText("PC unavailable — check connection, then Retry"); }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) { pageFailed = true; status.setText("PC returned " + response.getStatusCode() + " — Retry"); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                String[] types = params.getAcceptTypes();
                if (types != null && types.length > 0 && types[0].contains("/")) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                try { startActivityForResult(intent, PICK_FILE); } catch (ActivityNotFoundException e) { callback.onReceiveValue(null); fileCallback = null; }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                if (!relay.owns(request.getOrigin().toString()) || !java.util.Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) { request.deny(); return; }
                if (microphone != null) microphone.deny();
                microphone = request;
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) grantMicrophone();
                else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO);
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) { if (microphone == request) microphone = null; }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message message) {
                if (!userGesture) return false;
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        Uri uri = r.getUrl();
                        if (relay.owns(uri.toString())) web.loadUrl(uri.toString()); else openExternal(uri);
                        popup.destroy(); return true;
                    }
                });
                ((WebView.WebViewTransport)message.obj).setWebView(popup); message.sendToTarget(); return true;
            }
        });
        web.setDownloadListener((url, agent, disposition, mime, length) -> download(url, URLUtil.guessFileName(url, disposition, mime), mime));
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.loadUrl(relay.origin + "/");
    }
    private void grantMicrophone() {
        if (microphone == null) return;
        if (relay != null && relay.owns(microphone.getOrigin().toString())) microphone.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else microphone.deny();
        microphone = null;
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == AUDIO && microphone != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) grantMicrophone();
            else { microphone.deny(); microphone = null; }
        }
    }
    private void openExternal(Uri uri) {
        if (!java.util.Arrays.asList("https", "http", "mailto").contains(uri.getScheme())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "No app can open this link", Toast.LENGTH_LONG).show(); }
    }
    private void download(String url, String name, String mime) {
        if (preparingDownload || pendingDownload().exists()) { Toast.makeText(this, "Finish saving the current file first", Toast.LENGTH_SHORT).show(); return; }
        if (url.startsWith("blob:" + relay.origin + "/") || url.startsWith("data:")) {
            String script = "(async()=>{try{const r=await fetch(" + JSONObject.quote(url) + ");const b=await r.blob();if(b.size>" + MAX_DOWNLOAD + ")throw Error('File exceeds 50 MiB');const f=new FileReader();f.onload=()=>LearnHarnessDownloads.save(" + JSONObject.quote(relay.secret) + "," + JSONObject.quote(name) + "," + JSONObject.quote(mime == null ? "application/octet-stream" : mime) + ",String(f.result).split(',')[1]);f.readAsDataURL(b)}catch(e){alert('Download failed: '+e.message)}})()";
            web.evaluateJavascript(script, null); return;
        }
        if (!relay.owns(url)) { openExternal(Uri.parse(url)); return; }
        final String cookie = "lh_android=" + relay.secret;
        tasks.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)new URL(url).openConnection(); connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Cookie", cookie); connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
                if (connection.getResponseCode() != 200) throw new IOException("Download rejected");
                byte[] data;
                try (InputStream input = connection.getInputStream()) { data = readLimited(input); }
                runOnUiThread(() -> saveFile(name, mime, data));
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
            finally { if (connection != null) connection.disconnect(); }
        });
    }
    public final class DownloadBridge {
        @JavascriptInterface public void save(String capability, String name, String mime, String encoded) {
            LocalRelay current = relay;
            if (current == null || !current.secret.equals(capability) || encoded == null || encoded.length() > (MAX_DOWNLOAD * 4L / 3 + 8)) return;
            try { byte[] bytes = Base64.decode(encoded, Base64.DEFAULT); runOnUiThread(() -> { if (current == relay) saveFile(name, mime, bytes); }); }
            catch (IllegalArgumentException ignored) {}
        }
    }
    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[16384];
        for (int count; (count = input.read(buffer)) != -1;) { if (output.size() + count > MAX_DOWNLOAD) throw new IOException("File exceeds 50 MiB"); output.write(buffer, 0, count); }
        return output.toByteArray();
    }
    private void saveFile(String name, String mime, byte[] data) {
        if (preparingDownload || pendingDownload().exists() || isFinishing()) return;
        preparingDownload = true;
        tasks.execute(() -> {
            try {
                // Persist before leaving the activity: Android may reclaim this process
                // while the document provider is open. onActivityResult can resume it.
                try (FileOutputStream output = new FileOutputStream(pendingDownload())) { output.write(data); }
                runOnUiThread(() -> {
                    preparingDownload = false;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime == null || !mime.contains("/") ? "application/octet-stream" : mime);
                    intent.putExtra(Intent.EXTRA_TITLE, name.replaceAll("[/\\\\\\p{Cntrl}]", "_").substring(0, Math.min(name.length(), 160)));
                    try { startActivityForResult(intent, SAVE_FILE); } catch (ActivityNotFoundException e) { pendingDownload().delete(); Toast.makeText(this, "No document provider is available", Toast.LENGTH_LONG).show(); }
                });
            } catch (IOException e) { pendingDownload().delete(); runOnUiThread(() -> { preparingDownload = false; Toast.makeText(this, "Could not prepare download", Toast.LENGTH_LONG).show(); }); }
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == PICK_FILE && fileCallback != null) {
            Uri[] files = null;
            if (result == RESULT_OK && intent != null) {
                if (intent.getClipData() != null) { files = new Uri[intent.getClipData().getItemCount()]; for (int i = 0; i < files.length; i++) files[i] = intent.getClipData().getItemAt(i).getUri(); }
                else if (intent.getData() != null) files = new Uri[]{intent.getData()};
            }
            fileCallback.onReceiveValue(files); fileCallback = null;
        }
        if (request == SAVE_FILE) {
            if (result == RESULT_OK && intent != null && intent.getData() != null && pendingDownload().exists()) {
                Uri destination = intent.getData();
                tasks.execute(() -> {
                    try (InputStream input = new FileInputStream(pendingDownload()); OutputStream output = getContentResolver().openOutputStream(destination)) { if (output == null) throw new IOException("No writable file"); byte[] buffer = new byte[16384]; for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count); runOnUiThread(() -> Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()); }
                    catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Could not save file", Toast.LENGTH_LONG).show()); }
                    finally { pendingDownload().delete(); }
                });
            } else pendingDownload().delete();
        }
    }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() {
        generation++;
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (microphone != null) microphone.deny();
        if (web != null) web.destroy();
        if (relay != null) relay.close(); tasks.shutdownNow(); super.onDestroy();
    }
}
