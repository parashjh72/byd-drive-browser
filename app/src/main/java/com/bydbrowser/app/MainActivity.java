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
import android.os.Message;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;

/**
 * Drive Browser — a light, big-button, multi-tab web browser for BYD DiLink head units.
 *
 * - Tabs keep running when you switch away, so a song in one tab keeps playing while you browse another.
 * - A media notification (and the steering-wheel buttons) control whichever tab is playing.
 * - Background tabs that are NOT playing are paused and marked low priority to save memory.
 * - Driving check: when the car moves, asks once per trip "passenger" or "audio only".
 */
public class MainActivity extends Activity {

    static MainActivity instance;
    static final String EXTRA_SHOW_MEDIA_TAB = "show_media_tab";

    private static final String HOME = "file:///android_asset/home.html";
    private static final String SEARCH = "https://www.google.com/search?q=";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int MAX_TABS = 8;

    private static final int REQ_VOICE = 10;
    private static final int REQ_PERMS = 11;
    private static final float MOVING_KMH = 15f;
    private static final float STOPPED_KMH = 3f;
    private static final long TRIP_RESET_MS = 3 * 60 * 1000;

    /** One browser tab. Its WebView stays alive while the tab exists. */
    final class Tab {
        WebView web;
        String title = "New tab";
        String url;          // last known URL
        String pendingUrl;   // loaded the first time the tab is shown (lazy restore)
        boolean playing;
        String mediaTitle = "", mediaArtist = "";
    }

    /** JS → app bridge for one tab. Only receives media state; exposes nothing sensitive. */
    final class MediaBridge {
        private final Tab tab;
        MediaBridge(Tab t) { tab = t; }
        @JavascriptInterface
        public void state(boolean playing, String title, String artist) {
            runOnUiThread(() -> onMediaState(tab, playing, title, artist));
        }
    }

    private final ArrayList<Tab> tabs = new ArrayList<>();
    private Tab current;
    private Tab mediaTab;

    private FrameLayout root, webContainer, fullscreenHolder;
    private LinearLayout toolbar, tabStrip;
    private HorizontalScrollView tabScroll;
    private EditText address;
    private ProgressBar progress;
    private View audioOnlyCover, customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private Button desktopBtn;

    private SharedPreferences prefs;
    private String mediaJs = "";
    private boolean desktopMode, immersive, tripAcknowledged;
    private long stoppedSince = 0, lastBack = 0;
    private AlertDialog drivingDialog;
    private LocationManager locationManager;

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("browser", MODE_PRIVATE);
        desktopMode = prefs.getBoolean("desktop", true);
        mediaJs = readAsset("media.js");

        buildUi();
        restoreTabs();
        handleIntent(getIntent());
        requestPermissionsIfNeeded();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra(EXTRA_SHOW_MEDIA_TAB, false) && mediaTab != null) {
            audioOnlyCover.setVisibility(View.GONE);
            selectTab(mediaTab);
        } else if (i.getData() != null) {
            newTab(i.getData().toString());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (current != null) current.web.onResume();
        startSpeedWatch();
    }

    @Override
    protected void onPause() {
        // WebViews are NOT paused here: music keeps playing when you open Maps or the BYD home screen.
        super.onPause();
        saveTabs();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        stopSpeedWatch();
        MediaPlaybackService.stop(this);
        for (Tab t : tabs) t.web.destroy();
        tabs.clear();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Under memory pressure, unload background tabs that aren't playing (they reload when opened).
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            for (Tab t : tabs) {
                if (t != current && !t.playing && t.pendingUrl == null && t.url != null) {
                    t.pendingUrl = t.url;
                    t.web.loadUrl("about:blank");
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (audioOnlyCover.getVisibility() == View.VISIBLE) return;
        if (customView != null) { exitFullscreen(); return; }
        if (current != null && current.web.canGoBack()) current.web.goBack();
        else moveTaskToBack(true); // keep running (and playing) instead of closing
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (immersive && e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            long now = System.currentTimeMillis();
            if (now - lastBack < 600) { toggleImmersive(); return true; }
            lastBack = now;
        }
        return super.dispatchKeyEvent(e);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            return new String(buf, 0, Math.max(n, 0), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================== tabs

    private void restoreTabs() {
        String saved = prefs.getString("tabs", "");
        int cur = prefs.getInt("cur", 0);
        if (!saved.isEmpty()) {
            for (String u : saved.split("\n")) {
                if (u.isEmpty() || tabs.size() >= MAX_TABS) continue;
                Tab t = createTab();
                t.pendingUrl = u;
                t.url = u;
                t.title = shortTitle(u);
            }
        }
        if (tabs.isEmpty()) { newTab(HOME); return; }
        selectTab(tabs.get(Math.min(Math.max(cur, 0), tabs.size() - 1)));
    }

    private void saveTabs() {
        StringBuilder sb = new StringBuilder();
        for (Tab t : tabs) {
            String u = t.pendingUrl != null ? t.pendingUrl : (t.web.getUrl() != null ? t.web.getUrl() : t.url);
            if (u == null || u.equals("about:blank")) u = HOME;
            sb.append(u).append('\n');
        }
        prefs.edit().putString("tabs", sb.toString()).putInt("cur", tabs.indexOf(current)).apply();
    }

    private Tab createTab() {
        Tab t = new Tab();
        t.web = makeWebView(t);
        t.web.setVisibility(View.GONE);
        webContainer.addView(t.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabs.add(t);
        return t;
    }

    /** Opens a new tab (or reuses the current one if the tab limit is reached). */
    private Tab newTab(String url) {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Max " + MAX_TABS + " tabs — close one to open more", Toast.LENGTH_SHORT).show();
            if (url != null) current.web.loadUrl(url);
            return current;
        }
        Tab t = createTab();
        if (url != null) { t.url = url; t.web.loadUrl(url); }
        selectTab(t);
        return t;
    }

    private void selectTab(Tab t) {
        if (t == null) return;
        if (current != null && current != t) {
            current.web.setVisibility(View.GONE);
            backgroundTab(current);
        }
        current = t;
        t.web.setVisibility(View.VISIBLE);
        t.web.onResume();
        if (Build.VERSION.SDK_INT >= 26)
            t.web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        if (t.pendingUrl != null) {
            String u = t.pendingUrl;
            t.pendingUrl = null;
            t.web.loadUrl(u);
        }
        String u = t.web.getUrl() != null ? t.web.getUrl() : t.url;
        if (!address.hasFocus()) address.setText(u == null || u.startsWith(HOME) ? "" : u);
        progress.setVisibility(View.INVISIBLE);
        refreshTabStrip();
    }

    /** A hidden tab that isn't playing is paused and may have its renderer reclaimed. */
    private void backgroundTab(Tab t) {
        if (t.playing) return;
        t.web.onPause();
        if (Build.VERSION.SDK_INT >= 26)
            t.web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true);
    }

    private void closeTab(Tab t) {
        int idx = tabs.indexOf(t);
        if (idx < 0) return;
        if (t == mediaTab) { mediaTab = null; MediaPlaybackService.stop(this); }
        tabs.remove(idx);
        webContainer.removeView(t.web);
        t.web.destroy();
        if (tabs.isEmpty()) { current = null; newTab(HOME); return; }
        if (t == current) {
            current = null;
            selectTab(tabs.get(Math.min(idx, tabs.size() - 1)));
        } else {
            refreshTabStrip();
        }
    }

    private Tab tabFor(WebView v) {
        for (Tab t : tabs) if (t.web == v) return t;
        return null;
    }

    private static String shortTitle(String url) {
        if (url == null || url.startsWith(HOME)) return "Home";
        String h = Uri.parse(url).getHost();
        return h == null ? url : h.replaceFirst("^www\\.", "");
    }

    private void refreshTabStrip() {
        tabStrip.removeAllViews();
        for (Tab t : tabs) tabStrip.addView(tabChip(t));
        TextView plus = new TextView(this);
        plus.setText("＋");
        plus.setTextSize(24);
        plus.setTextColor(Color.WHITE);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(rounded(0xFF23272E, 12));
        plus.setOnClickListener(v -> newTab(HOME));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(44));
        lp.setMargins(dp(4), 0, dp(4), 0);
        tabStrip.addView(plus, lp);
        if (current != null) {
            int i = tabs.indexOf(current);
            tabScroll.post(() -> {
                View c = tabStrip.getChildAt(i);
                if (c != null) tabScroll.smoothScrollTo(Math.max(0, c.getLeft() - dp(40)), 0);
            });
        }
    }

    private View tabChip(Tab t) {
        boolean sel = t == current;
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(14), 0, dp(4), 0);
        chip.setBackground(rounded(sel ? 0xFF0B5FFF : 0xFF23272E, 12));

        TextView title = new TextView(this);
        title.setText((t.playing ? "🔊 " : "") + (t.title == null || t.title.isEmpty() ? "New tab" : t.title));
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(dp(200));
        chip.addView(title);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(sel ? Color.WHITE : 0xFF9AA0A6);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> closeTab(t));
        chip.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        chip.setOnClickListener(v -> selectTab(t));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.setMargins(dp(4), 0, dp(4), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    // ================================================================== media / notification

    private void onMediaState(Tab t, boolean playing, String title, String artist) {
        if (!tabs.contains(t)) return;
        boolean changed = t.playing != playing;
        t.playing = playing;
        t.mediaTitle = title == null ? "" : title;
        t.mediaArtist = artist == null ? "" : artist;
        if (playing) mediaTab = t;
        if (t == mediaTab) MediaPlaybackService.update(this, t.mediaTitle, t.mediaArtist, playing);
        if (!playing && t != current) backgroundTab(t);
        if (changed) refreshTabStrip();
    }

    private void runMedia(String fn) {
        Tab t = mediaTab != null ? mediaTab : current;
        if (t != null) t.web.evaluateJavascript("window.__dbCtl&&window.__dbCtl." + fn + "()", null);
    }

    void mediaPlay()  { runMedia("play"); }
    void mediaPause() { runMedia("pause"); }
    void mediaNext()  { runMedia("next"); }
    void mediaPrev()  { runMedia("prev"); }
    void mediaClose() {
        runMedia("pause");
        mediaTab = null;
        MediaPlaybackService.stop(this);
    }

    // ================================================================== UI

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Button barButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(rounded(0xFF23272E, 14));
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
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setBackgroundColor(0xFF121417);

        // --- tab strip
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setPadding(dp(4), dp(6), dp(4), 0);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabStrip);
        toolbar.addView(tabScroll);

        // --- navigation row
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));

        nav.addView(barButton("◀", v -> { if (current.web.canGoBack()) current.web.goBack(); }));
        nav.addView(barButton("▶", v -> { if (current.web.canGoForward()) current.web.goForward(); }));
        nav.addView(barButton("⟳", v -> current.web.reload()));
        nav.addView(barButton("⌂", v -> current.web.loadUrl(HOME)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(20);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(0xFF8A9099);
        address.setHint("Search or type a website");
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setBackground(rounded(0xFF23272E, 30));
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
        nav.addView(address);

        nav.addView(barButton("🎤", v -> startVoice()));
        desktopBtn = barButton(desktopMode ? "🖥" : "📱", v -> toggleDesktop());
        nav.addView(desktopBtn);
        nav.addView(barButton("⛶", v -> toggleImmersive()));
        nav.addView(barButton("🔊", v -> showAudioOnly()));
        toolbar.addView(nav);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        webContainer = new FrameLayout(this);
        webContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column.addView(toolbar);
        column.addView(progress);
        column.addView(webContainer);
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
        hint.setText("\nUse the steering-wheel buttons or the notification to skip songs.\nPress and hold anywhere to show the screen.");
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

    private void toggleImmersive() {
        immersive = !immersive;
        toolbar.setVisibility(immersive ? View.GONE : View.VISIBLE);
        if (immersive)
            Toast.makeText(this, "Press Back twice quickly to show the bar", Toast.LENGTH_LONG).show();
        applySystemUi();
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

    // ================================================================== browsing

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
        current.web.loadUrl(url);
    }

    private void toggleDesktop() {
        desktopMode = !desktopMode;
        prefs.edit().putBoolean("desktop", desktopMode).apply();
        for (Tab t : tabs) applyUserAgent(t.web);
        desktopBtn.setText(desktopMode ? "🖥" : "📱");
        Toast.makeText(this, desktopMode ? "Desktop sites" : "Mobile sites", Toast.LENGTH_SHORT).show();
        current.web.reload();
    }

    private void applyUserAgent(WebView w) {
        WebSettings s = w.getSettings();
        s.setUserAgentString(desktopMode ? UA_DESKTOP : null);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
    }

    private WebView makeWebView(Tab tab) {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);            // target=_blank → new tab
        s.setJavaScriptCanOpenWindowsAutomatically(false); // blocks pop-up spam
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setTextZoom(115);
        applyUserAgent(w);
        w.addJavascriptInterface(new MediaBridge(tab), "DriveMedia");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("http") || scheme.equals("https") || scheme.equals("file")
                        || scheme.equals("about")) return false;
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
                tab.url = url;
                tab.title = shortTitle(url);
                if (tab == current && !address.hasFocus()) address.setText(url.startsWith(HOME) ? "" : url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                view.evaluateJavascript(mediaJs, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(mediaJs, null);
                if (tab == current && !address.hasFocus()) address.setText(url.startsWith(HOME) ? "" : url);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The system reclaimed this tab's memory. Rebuild it instead of crashing.
                Tab t = tabFor(view);
                if (t == null) return true;
                int idx = webContainer.indexOfChild(view);
                webContainer.removeView(view);
                view.destroy();
                t.playing = false;
                t.web = makeWebView(t);
                t.web.setVisibility(t == current ? View.VISIBLE : View.GONE);
                webContainer.addView(t.web, Math.max(idx, 0), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                String u = t.url == null ? HOME : t.url;
                if (t == current) t.web.loadUrl(u); else t.pendingUrl = u;
                if (t == mediaTab) { mediaTab = null; MediaPlaybackService.stop(MainActivity.this); }
                refreshTabStrip();
                return true;
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                if (tab != current) return;
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.isEmpty() && !title.startsWith("http")) {
                    tab.title = title;
                    refreshTabStrip();
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message resultMsg) {
                if (!userGesture || tabs.size() >= MAX_TABS) return false;
                Tab nt = newTab(null);
                WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                tr.setWebView(nt.web);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                Tab t = tabFor(window);
                if (t != null) closeTab(t);
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

        w.setDownloadListener((url, ua, disposition, mime, length) -> {
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
        return w;
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

    // ================================================================== voice search

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
                String cleaned = r.get(0).replace(" dot ", ".");
                address.setText(cleaned);
                go(cleaned);
            }
        }
    }

    // ================================================================== permissions + driving check

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return;
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
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
                else if (now - stoppedSince > TRIP_RESET_MS) tripAcknowledged = false;
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
        if (isFinishing() || !hasWindowFocus()) return;
        if (drivingDialog != null && drivingDialog.isShowing()) return;
        if (audioOnlyCover.getVisibility() == View.VISIBLE) return;
        drivingDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("The car is moving")
                .setMessage("Who is using the screen?\n\nIf you're driving, switch to Audio only — "
                        + "music keeps playing and you can skip songs with the steering-wheel buttons.")
                .setCancelable(false)
                .setPositiveButton("I'm a passenger", (d, w) -> tripAcknowledged = true)
                .setNegativeButton("Audio only", (d, w) -> { tripAcknowledged = true; showAudioOnly(); })
                .create();
        drivingDialog.show();
        drivingDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextSize(20);
        drivingDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextSize(20);
    }
}
