package com.jarvis.assistant;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_MIC = 1;
    private static final int REQ_CAM = 2;
    private static final int CYAN = Color.parseColor("#00E5FF");
    private static final int GREEN = Color.parseColor("#00FF9C");
    private static final int AMBER = Color.parseColor("#FFB300");
    private static final int BLUE = Color.parseColor("#4DA3FF");
    private static final int BG = Color.parseColor("#0A0F1A");

    // If you get HTTP 404, the model name may have changed. Tell Claude.
    private static final String MODEL = "gemini-flash-latest";
    private static final String API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    private static final String SYSTEM_PROMPT =
            "You are J.A.R.V.I.S., the calm, polite, slightly witty AI assistant from Iron Man. "
            + "Address the user as boss. Reply in the same language the user used "
            + "(English, Tamil, or Tanglish). Keep replies short, at most 3 sentences. "
            + "Do not use markdown, bullet points, or emojis.";
    private static final int MAX_HISTORY = 12;
    private static final String[] WAKE = {"jarvis", "jarviz", "jarvish", "charvis", "darvis", "javis",
            "\u0B9C\u0BBE\u0BB0\u0BCD\u0BB5\u0BBF\u0BB8\u0BCD", "\u0B9C\u0BBE\u0BB5\u0BBF\u0BB8\u0BCD"};

    // UI
    private HudView hud;
    private TextView statusView;
    private TextView heardView;
    private TextView replyView;
    private TextView gestureView;
    private Button handsFreeBtn;
    private Button gestureBtn;
    private Button langBtn;
    private Button settingsBtn;
    private LinearLayout keyPanel;
    private EditText keyInput;

    // Voice
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listenRequested = false;
    private boolean speaking = false;
    private boolean thinking = false;
    private boolean tamil = false;
    private boolean handsFree = false;
    private boolean wantHandsFree = false;
    private boolean directMode = false;
    private boolean awaitingCommand = false;

    // Actions
    private Runnable pending = null;
    private boolean flashOn = false;

    // Gestures
    private GestureRecognizer gestureRecognizer;
    private ProcessCameraProvider cameraProvider;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private boolean gesturesOn = false;
    private long lastFrame = 0;
    private String lastGesture = "None";
    private int gestureCount = 0;
    private boolean fired = false;
    private long lastGestureAction = 0;

    // Session memory: each entry is {role, text}. role = "user" or "model"
    private final ArrayList<String[]> history = new ArrayList<>();

    private final Runnable handsFreeLoop = () -> {
        if (handsFree && !listenRequested && !speaking && !thinking) {
            startListening(false);
        }
    };

    // ---------------------------------------------------------------- HUD view

    static class HudView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private int color = Color.parseColor("#00E5FF");
        private float speed = 0.6f;
        private float level = 0f;
        private float angle = 0f;
        private long last = 0;

        HudView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
        }

        void setMode(int c, float s) {
            color = c;
            speed = s;
        }

        void setLevel(float l) {
            level = Math.max(0f, Math.min(1f, l));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = System.nanoTime();
            float dt = last == 0 ? 0.016f : (now - last) / 1e9f;
            last = now;
            angle += dt * 40f * speed;

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) * 0.95f;
            float pulse = 1f + 0.05f * (float) Math.sin(now / 3e8) + level * 0.25f;

            ring.setColor(color);
            ring.setAlpha(230);
            ring.setStrokeWidth(r * 0.04f);
            box.set(cx - r, cy - r, cx + r, cy + r);
            for (int i = 0; i < 4; i++) {
                canvas.drawArc(box, angle + i * 90f, 55f, false, ring);
            }

            float r2 = r * 0.78f;
            ring.setColor(color);
            ring.setAlpha(170);
            ring.setStrokeWidth(r * 0.025f);
            box.set(cx - r2, cy - r2, cx + r2, cy + r2);
            for (int i = 0; i < 6; i++) {
                canvas.drawArc(box, -angle * 1.6f + i * 60f, 35f, false, ring);
            }

            float r3 = r * 0.58f;
            ring.setColor(color);
            ring.setAlpha(120);
            ring.setStrokeWidth(r * 0.02f);
            for (int i = 0; i < 24; i++) {
                double a = Math.toRadians(angle * 0.5f + i * 15f);
                float cos = (float) Math.cos(a);
                float sin = (float) Math.sin(a);
                canvas.drawLine(cx + cos * r3, cy + sin * r3,
                        cx + cos * r3 * 0.9f, cy + sin * r3 * 0.9f, ring);
            }

            float cr = r * 0.42f * pulse;
            core.setShader(new RadialGradient(cx, cy, cr,
                    new int[]{Color.WHITE, color, color & 0x00FFFFFF},
                    new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, cr, core);

            postInvalidateOnAnimation();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) {
                        runOnUiThread(() -> onSpeechFinished());
                    }
                    @Override public void onError(String utteranceId) {
                        runOnUiThread(() -> onSpeechFinished());
                    }
                });
                speak("Jarvis online. At your service, boss.");
            } else {
                statusView.setText("Voice output not available");
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(handsFreeLoop);
        if (recognizer != null && listenRequested) {
            recognizer.cancel();
            listenRequested = false;
        }
        directMode = false;
        setHud(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (handsFree) scheduleListen(500);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        cameraExecutor.shutdown();
        try {
            if (gestureRecognizer != null) gestureRecognizer.close();
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("jarvis_prefs", MODE_PRIVATE);
    }

    private String getKey() {
        return prefs().getString("gemini_key", "");
    }

    private Button chip(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(CYAN);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(Color.parseColor("#0D2230"));
        g.setStroke(dp(1), CYAN);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout row(Button a, Button b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(a);
        r.addView(b);
        return r;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(36), dp(20), dp(12));

        TextView title = new TextView(this);
        title.setText("J.A.R.V.I.S");
        title.setTextSize(26);
        title.setTextColor(CYAN);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.3f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText(getKey().isEmpty()
                ? "Tap Settings and save your API key"
                : "Tap the arc reactor and speak");
        statusView.setTextSize(13);
        statusView.setTextColor(Color.parseColor("#7FA8B3"));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(6), 0, dp(6));
        root.addView(statusView);

        hud = new HudView(this);
        hud.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)));
        hud.setOnClickListener(v -> onMicTapped());
        root.addView(hud);

        gestureView = new TextView(this);
        gestureView.setText("Gestures off");
        gestureView.setTextSize(12);
        gestureView.setTextColor(Color.parseColor("#557080"));
        gestureView.setGravity(Gravity.CENTER);
        gestureView.setPadding(0, dp(4), 0, dp(4));
        root.addView(gestureView);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        heardView = new TextView(this);
        heardView.setText("You: ...");
        heardView.setTextSize(16);
        heardView.setTextColor(Color.WHITE);
        heardView.setPadding(0, 0, 0, dp(10));
        box.addView(heardView);

        replyView = new TextView(this);
        replyView.setText("Jarvis: ...");
        replyView.setTextSize(16);
        replyView.setTextColor(CYAN);
        box.addView(replyView);

        scroll.addView(box);
        root.addView(scroll);

        handsFreeBtn = chip("Hands-free: OFF");
        handsFreeBtn.setOnClickListener(v -> setHandsFree(!handsFree));
        gestureBtn = chip("Gestures: OFF");
        gestureBtn.setOnClickListener(v -> {
            if (gesturesOn) stopGestures();
            else requestGestures();
        });
        root.addView(row(handsFreeBtn, gestureBtn));

        langBtn = chip("Language: English");
        langBtn.setOnClickListener(v -> {
            tamil = !tamil;
            langBtn.setText(tamil ? "Language: Tamil" : "Language: English");
        });
        settingsBtn = chip("Settings (API key)");
        settingsBtn.setOnClickListener(v -> {
            boolean show = keyPanel.getVisibility() != View.VISIBLE;
            keyPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            hud.setVisibility(show ? View.GONE : View.VISIBLE);
        });
        root.addView(row(langBtn, settingsBtn));

        keyPanel = new LinearLayout(this);
        keyPanel.setOrientation(LinearLayout.VERTICAL);
        keyPanel.setVisibility(View.GONE);

        keyInput = new EditText(this);
        keyInput.setHint(getKey().isEmpty() ? "Paste Gemini API key" : "Key saved. Paste to replace");
        keyInput.setHintTextColor(Color.parseColor("#557080"));
        keyInput.setTextColor(Color.WHITE);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyPanel.addView(keyInput);

        Button saveBtn = new Button(this);
        saveBtn.setText("Save key");
        saveBtn.setAllCaps(false);
        saveBtn.setOnClickListener(v -> {
            String k = keyInput.getText().toString().trim();
            if (k.isEmpty()) {
                statusView.setText("Key box is empty");
                return;
            }
            if (k.matches(".*\\s.*") || k.toLowerCase(Locale.ROOT).contains("curl")
                    || k.length() < 20 || k.length() > 100) {
                statusView.setText("This is not a key. Paste only the key text, one line");
                return;
            }
            prefs().edit().putString("gemini_key", k).apply();
            keyInput.setText("");
            keyInput.setHint("Key saved. Paste to replace");
            keyPanel.setVisibility(View.GONE);
            hud.setVisibility(View.VISIBLE);
            statusView.setText("Key saved. Tap the arc reactor and speak");
        });
        keyPanel.addView(saveBtn);
        root.addView(keyPanel);

        setContentView(root);
    }

    private void setHud(int state) {
        switch (state) {
            case 1: hud.setMode(GREEN, 2.2f); break;
            case 2: hud.setMode(AMBER, 4.5f); break;
            case 3: hud.setMode(BLUE, 1.6f); break;
            default:
                hud.setMode(CYAN, 0.6f);
                hud.setLevel(0f);
        }
    }

    // ---------------------------------------------------------------- permissions

    private boolean hasMic() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC) {
            if (granted) {
                if (wantHandsFree) {
                    wantHandsFree = false;
                    setHandsFree(true);
                } else {
                    startListening(true);
                }
            } else {
                wantHandsFree = false;
                statusView.setText("Mic permission denied");
            }
        } else if (requestCode == REQ_CAM) {
            if (granted) startGestures();
            else statusView.setText("Camera permission denied");
        }
    }

    // ---------------------------------------------------------------- listening

    private void onMicTapped() {
        if (listenRequested) {
            if (handsFree && !directMode && recognizer != null) {
                recognizer.cancel();
                listenRequested = false;
                startListening(true);
            } else if (recognizer != null) {
                recognizer.stopListening();
            }
            return;
        }
        if (!hasMic()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startListening(true);
    }

    private void setHandsFree(boolean on) {
        if (on && !hasMic()) {
            wantHandsFree = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        handsFree = on;
        handsFreeBtn.setText(on ? "Hands-free: ON" : "Hands-free: OFF");
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            statusView.setText("Say \"Hey Jarvis\" and your command");
            scheduleListen(300);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            handler.removeCallbacks(handsFreeLoop);
            if (recognizer != null && listenRequested) {
                recognizer.cancel();
            }
            listenRequested = false;
            directMode = false;
            awaitingCommand = false;
            setHud(0);
            statusView.setText("Tap the arc reactor and speak");
        }
    }

    private void scheduleListen(long ms) {
        handler.removeCallbacks(handsFreeLoop);
        if (handsFree) handler.postDelayed(handsFreeLoop, ms);
    }

    private void ensureRecognizer() 
