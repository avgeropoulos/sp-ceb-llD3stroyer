package com.backyardboop.game;

import android.app.Activity;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Full-screen WebView that runs the game bundled in assets/game (2D or 3D, per flavor). */
public class MainActivity extends Activity {
    private WebView web;
    /** Only the 3D game reads the controller this way; the classic game keeps WebView's own keys. */
    private PadBridge pad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // high scores and settings
        s.setMediaPlaybackRequiresUserGesture(false); // music and sound effects
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(0xFF2F6B2A);
        setContentView(web);
        hideSystemBars();

        if ("3d.html".equals(getString(R.string.start_page))) {
            pad = new PadBridge();
            web.addJavascriptInterface(pad, "BBPad");
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/game/" + getString(R.string.start_page));
    }

    private void hideSystemBars() {
        web.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // Controllers: buttons and sticks go straight to the page, whatever has focus.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (pad != null && fromController(e)) {
            int bit = PadBridge.bitFor(e.getKeyCode());
            if (bit != 0) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) pad.key(bit, true);
                else if (e.getAction() == KeyEvent.ACTION_UP) pad.key(bit, false);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (pad != null && e.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK) && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float rx = e.getAxisValue(MotionEvent.AXIS_Z), ry = e.getAxisValue(MotionEvent.AXIS_RZ);
            if (rx == 0f && ry == 0f) { rx = e.getAxisValue(MotionEvent.AXIS_RX); ry = e.getAxisValue(MotionEvent.AXIS_RY); }
            float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            pad.axes(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y), rx, ry,
                    e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y), lt > 0.5f, rt > 0.5f);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private static boolean fromController(InputEvent e) {
        int s = e.getSource();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (e instanceof KeyEvent && KeyEvent.isGamepadButton(((KeyEvent) e).getKeyCode()));
    }

    /**
     * Controller state for the page. Android delivers buttons and sticks here and the
     * game reads them once a frame with poll(): "lx,ly,rx,ry,hatX,hatY,held,tapped,seen".
     * "tapped" remembers presses between polls so a very quick tap still counts.
     */
    static final class PadBridge {
        static final int A = 1, B = 2, X = 4, Y = 8, START = 16, SELECT = 32, L1 = 64, R1 = 128,
                UP = 256, DOWN = 512, LEFT = 1024, RIGHT = 2048, L2 = 4096, R2 = 8192;
        private float lx, ly, rx, ry, hx, hy;
        private int held, tapped, triggers;
        private boolean seen;

        static int bitFor(int code) {
            switch (code) {
                case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: return A;
                case KeyEvent.KEYCODE_BUTTON_B: return B;
                case KeyEvent.KEYCODE_BUTTON_X: return X;
                case KeyEvent.KEYCODE_BUTTON_Y: return Y;
                case KeyEvent.KEYCODE_BUTTON_START: return START;
                case KeyEvent.KEYCODE_BUTTON_SELECT: return SELECT;
                case KeyEvent.KEYCODE_BUTTON_L1: return L1;
                case KeyEvent.KEYCODE_BUTTON_R1: return R1;
                case KeyEvent.KEYCODE_BUTTON_L2: return L2;
                case KeyEvent.KEYCODE_BUTTON_R2: return R2;
                case KeyEvent.KEYCODE_DPAD_UP: return UP;
                case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
                case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
                case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
                default: return 0;
            }
        }

        synchronized void key(int bit, boolean down) {
            seen = true;
            if (down) { held |= bit; tapped |= bit; } else held &= ~bit;
        }

        synchronized void axes(float lx, float ly, float rx, float ry, float hx, float hy, boolean l2, boolean r2) {
            seen = true;
            this.lx = lx; this.ly = ly; this.rx = rx; this.ry = ry; this.hx = hx; this.hy = hy;
            int t = (l2 ? L2 : 0) | (r2 ? R2 : 0);
            tapped |= t & ~triggers;
            triggers = t;
        }

        @JavascriptInterface
        public synchronized String poll() {
            String s = lx + "," + ly + "," + rx + "," + ry + "," + hx + "," + hy + "," + (held | triggers) + "," + tapped + "," + (seen ? 1 : 0);
            tapped = 0;
            return s;
        }
    }

    /** Back pauses the game or returns to the menu; on the menu it leaves the app. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.bbBack ? window.bbBack() : 'exit'", result -> {
            if (result == null || result.contains("exit")) finish();
        });
    }

    @Override
    protected void onPause() {
        web.evaluateJavascript("window.bbAppPaused && window.bbAppPaused()", null);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.evaluateJavascript("window.bbAppResumed && window.bbAppResumed()", null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
