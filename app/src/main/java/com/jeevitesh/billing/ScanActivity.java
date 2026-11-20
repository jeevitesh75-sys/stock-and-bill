package com.jeevitesh.billing;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portrait camera scanner.
 *  mode "multi":  stays open. Every scan adds 1 piece (same barcode scanned again adds another).
 *                 Beeps, vibrates, shows a running list. OK returns {"barcode": count, ...}.
 *  mode "single": returns the first barcode it sees (used for adding stock).
 */
public class ScanActivity extends Activity {

    private static final int REQ_CAM = 77;

    private DecoratedBarcodeView view;
    private TextView listView, statusView, titleView;
    private Button okBtn;
    private boolean multi;
    private JSONObject known = new JSONObject();
    private final LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
    private String last = "";
    private long lastSeen = 0;
    private ToneGenerator tone;
    private Vibrator vib;
    private boolean torch = false;
    private boolean started = false;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        multi = "multi".equals(getIntent().getStringExtra("mode"));
        try {
            String p = getIntent().getStringExtra("products");
            if (p != null) known = new JSONObject(p);
        } catch (Exception ignored) {
        }
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        } catch (Throwable ignored) {
        }
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0D10"));

        view = new DecoratedBarcodeView(this);
        List<BarcodeFormat> fmts = new ArrayList<BarcodeFormat>();
        fmts.add(BarcodeFormat.EAN_13);
        fmts.add(BarcodeFormat.EAN_8);
        fmts.add(BarcodeFormat.UPC_A);
        fmts.add(BarcodeFormat.UPC_E);
        fmts.add(BarcodeFormat.CODE_128);
        fmts.add(BarcodeFormat.CODE_39);
        fmts.add(BarcodeFormat.CODE_93);
        fmts.add(BarcodeFormat.ITF);
        fmts.add(BarcodeFormat.CODABAR);
        fmts.add(BarcodeFormat.QR_CODE);
        fmts.add(BarcodeFormat.DATA_MATRIX);
        view.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(fmts));
        view.setStatusText("");
        root.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#14181F"));
        panel.setPadding(dp(16), dp(12), dp(16), dp(16));

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setText(multi ? "Scan items. 0 scanned" : "Point the camera at the barcode");
        panel.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#F59E0B"));
        statusView.setTextSize(14);
        panel.addView(statusView);

        if (multi) {
            ScrollView sv = new ScrollView(this);
            listView = new TextView(this);
            listView.setTextColor(Color.parseColor("#DDE2EA"));
            listView.setTextSize(16);
            listView.setLineSpacing(0, 1.25f);
            listView.setText("Nothing scanned yet.");
            sv.addView(listView);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
            lp.topMargin = dp(8);
            panel.addView(sv, lp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button cancel = mk("Cancel", "#2A303A", Color.WHITE);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        Button tbtn = mk("Light", "#2A303A", Color.WHITE);
        tbtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                torch = !torch;
                if (torch) view.setTorchOn(); else view.setTorchOff();
            }
        });
        row.addView(cancel, btnLp());
        row.addView(tbtn, btnLp());
        if (multi) {
            okBtn = mk("OK", "#22C55E", Color.parseColor("#04130A"));
            okBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finishMulti();
                }
            });
            LinearLayout.LayoutParams lp2 = btnLp();
            lp2.weight = 1.4f;
            row.addView(okBtn, lp2);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(12);
        panel.addView(row, rl);

        root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        }
    }

    private Button mk(String t, String bg, int fg) {
        Button bt = new Button(this);
        bt.setText(t);
        bt.setAllCaps(false);
        bt.setTextSize(16);
        bt.setTextColor(fg);
        bt.setBackgroundColor(Color.parseColor(bg));
        return bt;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.rightMargin = dp(8);
        return lp;
    }

    private void startScan() {
        if (started) return;
        started = true;
        view.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (result == null || result.getText() == null) return;
                onCode(result.getText().trim());
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {
            }
        });
        view.resume();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else if (code == REQ_CAM) {
            Intent i = new Intent();
            i.putExtra("error", "Camera permission was not allowed. Allow Camera for this app in phone Settings.");
            setResult(RESULT_CANCELED, i);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) view.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tone != null) tone.release();
    }

    private void onCode(String code) {
        if (code.isEmpty()) return;
        long now = System.currentTimeMillis();
        // the same barcode staying in front of the camera counts once; move it away (about 0.8 s) to count it again
        boolean same = code.equals(last) && now - lastSeen < 800;
        last = code;
        lastSeen = now;
        if (same) return;

        if (!multi) {
            beep(true);
            vibrate();
            Intent i = new Intent();
            i.putExtra("code", code);
            setResult(RESULT_OK, i);
            finish();
            return;
        }

        JSONObject info = known.optJSONObject(code);
        Integer have = counts.get(code);
        int h = have == null ? 0 : have;
        if (info != null && h + 1 > info.optInt("q", 0)) {
            statusView.setText("Only " + info.optInt("q", 0) + " in stock: " + info.optString("n", code));
            beep(false);
            vibrate();
            return;
        }
        statusView.setText(info == null ? "Not in stock list: " + code : "");
        counts.put(code, h + 1);
        beep(true);
        vibrate();
        refresh();
    }

    private void refresh() {
        int total = 0;
        for (Integer v : counts.values()) total += v;
        titleView.setText("Scanned: " + total + " item" + (total == 1 ? "" : "s"));
        List<String> keys = new ArrayList<String>(counts.keySet());
        StringBuilder sb = new StringBuilder();
        for (int i = keys.size() - 1; i >= 0; i--) {
            String c = keys.get(i);
            JSONObject info = known.optJSONObject(c);
            String name = info == null ? "Not in stock list: " + c : info.optString("n", c);
            sb.append(name).append("  × ").append(counts.get(c)).append('\n');
        }
        listView.setText(sb.toString());
    }

    private void beep(boolean ok) {
        try {
            if (tone != null) tone.startTone(ok ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_PROP_NACK, ok ? 120 : 250);
        } catch (Throwable ignored) {
        }
    }

    private void vibrate() {
        try {
            if (vib == null) return;
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
            else vib.vibrate(60);
        } catch (Throwable ignored) {
        }
    }

    private void finishMulti() {
        if (counts.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Integer> e : counts.entrySet()) o.put(e.getKey(), e.getValue());
            Intent i = new Intent();
            i.putExtra("result", o.toString());
            setResult(RESULT_OK, i);
        } catch (Exception e) {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
