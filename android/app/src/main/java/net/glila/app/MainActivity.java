package net.glila.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows the web app from the APK's assets. The assets are served from an https origin
 * (appassets.androidplatform.net), so localStorage and the search APIs behave as on the website.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_PICK_FILE = 1;
    private static final int REQ_SAVE_FILE = 2;

    // The page saves its backup by clicking a link to a blob: URL, which WebView cannot download.
    // This hands the file's text to the app, which asks where to save it.
    private static final String SAVE_SHIM =
        "(function(){if(window.__glilaShim)return;window.__glilaShim=1;"
        + "var c=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){"
        + "var a=this;if(a.download&&/^blob:/.test(a.href)){"
        + "fetch(a.href).then(function(r){return r.text();})"
        + ".then(function(t){GlilaAndroid.saveFile(a.download,t);});return;}"
        + "return c.apply(this,arguments);};})();";

    // Feeds and news are read by the app, because those sites don't let a web page read them.
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private final ExecutorService net = Executors.newFixedThreadPool(4);

    private WebView web;
    private WebViewAssetLoader assets;
    private ValueCallback<Uri[]> pickCallback;
    private String pendingSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        assets = new WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }
        });

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.addJavascriptInterface(new Bridge(), "GlilaAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(SAVE_SHIM, null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(intent, REQ_PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    pickCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, uri.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void fetchText(String id, String address) {
            net.execute(() -> {
                try {
                    reply(id, true, download(address));
                } catch (Exception e) {
                    reply(id, false, String.valueOf(e.getMessage()));
                }
            });
        }

        @JavascriptInterface
        public void saveFile(String name, String text) {
            runOnUiThread(() -> {
                pendingSave = text;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(intent, REQ_SAVE_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingSave = null;
                }
            });
        }
    }

    // A plain GET of a public https address: no cookies, nothing sent, and never an address
    // inside the phone or the home network.
    private static String download(String address) throws Exception {
        URL url = new URL(address);
        for (int hops = 0; hops < 5; hops++) {
            if (!"https".equals(url.getProtocol())) throw new Exception("https only");
            for (InetAddress a : InetAddress.getAllByName(url.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                        || a.isAnyLocalAddress() || a.isMulticastAddress()
                        || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc)) {
                    throw new Exception("local address");
                }
            }
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Glila");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(url, c.getHeaderField("Location"));
                    continue;
                }
                if (code != 200) throw new Exception("http " + code);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        if (out.size() > MAX_BYTES) throw new Exception("too big");
                    }
                    return out.toString("UTF-8");
                }
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    private void reply(String id, boolean ok, String text) {
        String js = "window.__glilaFetched&&window.__glilaFetched(" + JSONObject.quote(id) + ","
            + ok + "," + JSONObject.quote(text) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_PICK_FILE && pickCallback != null) {
            pickCallback.onReceiveValue(uri != null ? new Uri[] { uri } : null);
            pickCallback = null;
        } else if (requestCode == REQ_SAVE_FILE) {
            String text = pendingSave;
            pendingSave = null;
            if (uri == null || text == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
