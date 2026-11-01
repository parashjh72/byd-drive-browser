package com.bydbrowser.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;
import java.util.ArrayList;

/**
 * Drive Browser — a big-button, car-screen friendly web browser for BYD DiLink head units.
 *
 * Driving safety: when the car is moving (GPS speed) the app asks once per trip whether a
 * passenger is using the screen. The driver can pick "Audio only", which blanks the screen
 * while music / video sound keeps playing.
 */
public class MainActivity extends Activity {

    private static final String HOME = "file:///android_asset/home.html";
    private static final String SEARCH = "https://www.google.com/search?q=";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final int REQ_VOICE = 10;
    private static final int REQ_PERMS = 11;
    private static final float MOVING_KMH = 15f;
    private static final float STOPPED_KMH = 3f;
    private static final long TRIP_RESET_MS = 3 * 60 * 1000;

    private WebView web;
    private EditText address;
    private ProgressBar progress;
    private LinearLayout toolbar;
    private FrameLayout root, fullscreenHolder;
    private View audioOnlyCover;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private Button desktopBtn;

    private SharedPreferences prefs;
    private boolean desktopMode;
    private boolean tripAcknowledged;
    private long stoppedSince = 0;
    private AlertDialog drivingDialog;
    private LocationManager locationManager;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("browser", MODE_PRIVATE);
        desktopMode = prefs.getBoolean("desktop", true); // car screens are wide: default desktop

        buildUi();
        configureWebView();

        String start = getIntent() != null && getIntent().getData() != null
                ? getIntent().getData().toString()
                : prefs.getString("last", HOME);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(start);

        requestPermissionsIfNeeded();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) web.loadUrl(intent.getData().toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        startSpeedWatch();
    }

    @Override
    protected void onPause() {
        // Intentionally NOT calling web.onPause(): keeps music / podcasts playing
        // when you switch to Maps or the BYD home screen.
        super.onPause();
        prefs.edit().putString("last", web.getUrl() == null ? HOME : web.getUrl()).apply();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        stopSpeedWatch();
        web.destroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (audioOnlyCover.getVisibility() == View.VISIBLE) return;
        if (customView != null) { exitFullscreen(); return; }
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button barButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF23272E);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setMinWidth(dp(64));
        b.setMinimumWidth(dp(64));
        b.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(60));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF121417);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        toolbar.setBackgroundColor(0xFF121417);

        toolbar.addView(barButton("◀", v -> { if (web.canGoBack()) web.goBack(); }));
        toolbar.addView(barButton("▶", v -> { if (web.canGoForward()) web.goForward(); }));
        toolbar.addView(barButton("⟳", v -> web.reload()));
        toolbar.addView(barButton("⌂", v -> web.loadUrl(HOME)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(20);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(0xFF8A9099);
        address.setHint("Search or type a website");
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(0xFF23272E);
        fieldBg.setCornerRadius(dp(30));
        address.setBackground(fieldBg);
        address.setPadding(dp(22), 0, dp(22), 0);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(60), 1f);
        alp.setMargins(dp(6), 0, dp(6), 0);
        address.setLayoutParams(alp);
        address.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_GO || enter) {
                go(address.getText().toString());
                return true;
            }
            return false;
        });
        toolbar.addView(address);

        toolbar.addView(barButton("🎤", v -> startVoice()));
        desktopBtn = barButton(desktopMode ? "🖥" : "📱", v -> toggleDesktop());
        toolbar.addView(desktopBtn);
        toolbar.addView(barButton("⛶", v -> toggleImmersive()));
        toolbar.addView(barButton("🔊", v -> showAudioOnly()));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        web = new WebView(this);
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column.addView(toolbar);
        column.addView(progress);
        column.addView(web);
        root.addView(column);

        fullscreenHolder = new FrameLayout(this);
        fullscreenHolder.setBackgroundColor(Color.BLACK);
        fullscreenHolder.setVisibility(View.GONE);
        root.addView(fullscreenHolder);

        audioOnlyCover = buildAudioOnlyCover();
        root.addView(audioOnlyCover);

        setContentView(root);
    }

    private View buildAudioOnlyCover() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setBackgroundColor(Color.BLACK);
        c.setClickable(true);
        c.setVisibility(View.GONE);

        TextView t = new TextView(this);
        t.setText("🔊  Audio only\nSound keeps playing — eyes on the road");
        t.setTextColor(0xFFB0B6BF);
        t.setTextSize(26);
        t.setGravity(Gravity.CENTER);
        c.addView(t);

        TextView hint = new TextView(this);
        hint.setText("\nPress and hold anywhere to show the screen");
        hint.setTextColor(0xFF6B7280);
        hint.setTextSize(18);
        hint.setGravity(Gravity.CENTER);
        c.addView(hint);

        c.setOnLongClickListener(v -> { c.setVisibility(View.GONE); return true; });
        return c;
    }

    private void showAudioOnly() {
        hideKeyboard();
        audioOnlyCover.setVisibility(View.VISIBLE);
    }

    private boolean immersive = false;

    private void toggleImmersive() {
        immersive = !immersive;
        toolbar.setVisibility(immersive ? View.GONE : View.VISIBLE);
        if (immersive) {
            Toast.makeText(this, "Press Back twice quickly or swipe from the top to show the bar",
                    Toast.LENGTH_LONG).show();
        }
        applySystemUi();
    }

    private long lastBack = 0;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (immersive && e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            long now = System.currentTimeMillis();
            if (now - lastBack < 600) { toggleImmersive(); return true; }
            lastBack = now;
        }
        return super.dispatchKeyEvent(e);
    }

    @SuppressWarnings("deprecation")
    private void applySystemUi() {
        int flags = (immersive || customView != null)
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_FLAG_VISIBLE;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
    }

    // ------------------------------------------------------------------ browsing

    private void go(String input) {
        hideKeyboard();
        String s = input.trim();
        if (s.isEmpty()) return;
        String url;
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://")) {
            url = s;
        } else if (!s.contains(" ") && s.contains(".")) {
            url = "https://" + s;
        } else {
            try { url = SEARCH + URLEncoder.encode(s, "UTF-8"); }
            catch (Exception e) { url = SEARCH + s; }
        }
        web.loadUrl(url);
    }

    private void toggleDesktop() {
        desktopMode = !desktopMode;
        prefs.edit().putBoolean("desktop", desktopMode).apply();
        applyUserAgent();
        desktopBtn.setText(desktopMode ? "🖥" : "📱");
        Toast.makeText(this, desktopMode ? "Desktop sites" : "Mobile sites", Toast.LENGTH_SHORT).show();
        web.reload();
    }

    private void applyUserAgent() {
        WebSettings s = web.getSettings();
        s.setUserAgentString(desktopMode ? UA_DESKTOP : null);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setTextZoom(115); // slightly larger text for arm's-length screens
        applyUserAgent();

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("http") || scheme.equals("https") || scheme.equals("file")) return false;
                // intent:, tel:, market:, spotify: ... hand to the system if an app exists
                try {
                    Intent i = scheme.equals("intent")
                            ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                            : new Intent(Intent.ACTION_VIEW, u);
                    startActivity(i);
                } catch (Exception ignored) {
                    if (scheme.equals("intent")) {
                        try {
                            String fb = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                                    .getStringExtra("browser_fallback_url");
                            if (fb != null) view.loadUrl(fb);
                        } catch (Exception ignored2) { }
                    }
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!address.hasFocus()) address.setText(url.startsWith(HOME) ? "" : url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!address.hasFocus()) address.setText(url.startsWith(HOME) ? "" : url);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = cb;
                fullscreenHolder.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenHolder.setVisibility(View.VISIBLE);
                applySystemUi();
            }

            @Override
            public void onHideCustomView() { exitFullscreen(); }
        });

        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            try {
                String name = URLUtil.guessFileName(url, disposition, mime);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                r.addRequestHeader("User-Agent", ua);
                r.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreenHolder.removeView(customView);
        fullscreenHolder.setVisibility(View.GONE);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        applySystemUi();
    }

    // ------------------------------------------------------------------ voice search

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a website or what to search");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No voice recognizer installed on this head unit", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOICE && res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                String spoken = r.get(0);
                // "youtube dot com" -> youtube.com
                String cleaned = spoken.replace(" dot ", ".");
                address.setText(cleaned);
                go(cleaned);
            }
        }
    }

    // ------------------------------------------------------------------ driving safety

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return;
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMS) startSpeedWatch();
    }

    private final LocationListener speedListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            if (!loc.hasSpeed()) return;
            float kmh = loc.getSpeed() * 3.6f;
            long now = System.currentTimeMillis();
            if (kmh >= MOVING_KMH) {
                stoppedSince = 0;
                if (!tripAcknowledged) showDrivingPrompt();
            } else if (kmh <= STOPPED_KMH) {
                if (stoppedSince == 0) stoppedSince = now;
                else if (now - stoppedSince > TRIP_RESET_MS) tripAcknowledged = false; // new trip
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    private void startSpeedWatch() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return;
        try {
            if (locationManager == null) locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, speedListener);
        } catch (Exception ignored) { }
    }

    private void stopSpeedWatch() {
        try { if (locationManager != null) locationManager.removeUpdates(speedListener); }
        catch (Exception ignored) { }
    }

    private void showDrivingPrompt() {
        if (drivingDialog != null && drivingDialog.isShowing()) return;
        if (audioOnlyCover.getVisibility() == View.VISIBLE) return;
        drivingDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("The car is moving")
                .setMessage("Who is using the screen?\n\nIf you're driving, switch to Audio only — "
                        + "music and video sound keep playing and you can use 🎤 voice search.")
                .setCancelable(false)
                .setPositiveButton("I'm a passenger", (d, w) -> tripAcknowledged = true)
                .setNegativeButton("Audio only", (d, w) -> { tripAcknowledged = true; showAudioOnly(); })
                .create();
        drivingDialog.show();
        drivingDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextSize(20);
        drivingDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextSize(20);
    }
}
