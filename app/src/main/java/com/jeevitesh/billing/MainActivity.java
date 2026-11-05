package com.jeevitesh.billing;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;


import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thin native shell around the web app in assets/index.html.
 * The web app calls window.Android.* for the three things a web page cannot do by itself:
 *   scan()                 -> camera scanner screen (ZXing, offline), answer comes back to window.onNativeScan(code)
 *   pickFolder()/readFile/writeFile -> a folder the user chooses on the phone (data survives uninstall)
 *   share(text)            -> Android share sheet (WhatsApp etc.)
 */
public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 4101;
    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("billing", MODE_PRIVATE);

        getWindow().setStatusBarColor(Color.parseColor("#0B0D10"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B0D10"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0D10"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setSupportZoom(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    /** Run JavaScript in the page from any thread. */
    private void js(final String code) {
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    private Uri treeUri() {
        String s = prefs.getString("tree", null);
        return s == null ? null : Uri.parse(s);
    }

    private boolean hasPermission(Uri tree) {
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isReadPermission() && p.isWritePermission()) return true;
        }
        return false;
    }

    /** Finds a file by name directly inside the chosen folder. */
    private Uri findChild(Uri tree, String name) {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static String mimeFor(String name) {
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".csv")) return "text/csv";
        return "application/octet-stream";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IntentIntegrator.REQUEST_CODE) {
            IntentResult r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
            if (r == null || r.getContents() == null) {
                js("window.onNativeScanCancel()");
            } else {
                js("window.onNativeScan(" + JSONObject.quote(r.getContents()) + ")");
            }
            return;
        }
        if (requestCode != REQ_FOLDER) return;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            prefs.edit().putString("tree", u.toString()).apply();
            js("window.onFolderChosen && window.onFolderChosen(true)");
        } else {
            js("window.onFolderChosen && window.onFolderChosen(false)");
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.handleBack ? window.handleBack() : false)", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"true".equals(value)) moveTaskToBack(true);
            }
        });
    }

    /** Everything the web page is allowed to ask the phone to do. */
    private class Bridge {

        @JavascriptInterface
        public boolean hasFolder() {
            Uri t = treeUri();
            return t != null && hasPermission(t);
        }

        /** Readable name of the chosen folder, e.g. "Documents/BillingData". */
        @JavascriptInterface
        public String folderName() {
            Uri t = treeUri();
            if (t == null) return "";
            try {
                String id = DocumentsContract.getTreeDocumentId(t);
                int i = id.indexOf(':');
                return i >= 0 ? id.substring(i + 1) : id;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(i, REQ_FOLDER);
                }
            });
        }

        /** "" when the file does not exist yet, "ERR:..." on a problem, otherwise the file text. */
        @JavascriptInterface
        public String readFile(String name) {
            try {
                Uri t = treeUri();
                if (t == null) return "ERR:no folder chosen";
                Uri f = findChild(t, name);
                if (f == null) return "";
                InputStream in = getContentResolver().openInputStream(f);
                if (in == null) return "ERR:cannot open " + name;
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        /** "ok" or "ERR:...". Replaces the whole file. */
        @JavascriptInterface
        public String writeFile(String name, String text) {
            try {
                Uri t = treeUri();
                if (t == null) return "ERR:no folder chosen";
                Uri f = findChild(t, name);
                if (f == null) {
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
                    f = DocumentsContract.createDocument(getContentResolver(), parent, mimeFor(name), name);
                }
                if (f == null) return "ERR:cannot create " + name;
                OutputStream out = getContentResolver().openOutputStream(f, "wt");
                if (out == null) return "ERR:cannot write " + name;
                try {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } finally {
                    out.close();
                }
                return "ok";
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        /** Opens the scanner. The result arrives in window.onNativeScan / onNativeScanCancel / onNativeScanError. */
        @JavascriptInterface
        public void scan() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    IntentIntegrator ig = new IntentIntegrator(MainActivity.this);
                    ig.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES);
                    ig.setPrompt("Point the camera at the barcode");
                    ig.setBeepEnabled(true);
                    ig.setOrientationLocked(false);
                    ig.initiateScan();
                }
            });
        }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Share"));
                }
            });
        }
    }
}
