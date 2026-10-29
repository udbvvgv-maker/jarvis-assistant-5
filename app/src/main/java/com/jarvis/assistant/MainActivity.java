package com.jarvis.assistant;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

public class MainActivity extends AppCompatActivity {

    private static final int REQ_MIC = 1;
    private static final int CYAN = Color.parseColor("#00E5FF");
    private static final int BG = Color.parseColor("#0A0F1A");

    // If you get HTTP 404, the model name may have changed. Tell Claude.
    private static final String MODEL = "gemini-2.5-flash";
    private static final String API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    private static final String SYSTEM_PROMPT =
            "You are Jarvis, a helpful voice assistant. Reply in the same language the user used "
            + "(English, Tamil, or Tanglish). Keep replies short, at most 3 sentences. "
            + "Do not use markdown, bullet points, or emojis.";
    private static final int MAX_HISTORY = 12;

    private TextView statusView;
    private TextView heardView;
    private TextView replyView;
    private Button micButton;
    private Button langButton;
    private Button settingsButton;
    private LinearLayout keyPanel;
    private EditText keyInput;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listening = false;
    private boolean tamil = false;

    // Session memory: each entry is {role, text}. role = "user" or "model"
    private final ArrayList<String[]> history = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                speak("Jarvis online.");
            } else {
                statusView.setText("Voice output not available");
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("jarvis_prefs", MODE_PRIVATE);
    }

    private String getKey() {
        return prefs().getString("gemini_key", "");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(16));

        TextView title = new TextView(this);
        title.setText("J.A.R.V.I.S");
        title.setTextSize(28);
        title.setTextColor(CYAN);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.3f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText(getKey().isEmpty()
                ? "Tap Settings and save your API key"
                : "Tap the mic and speak");
        statusView.setTextSize(14);
        statusView.setTextColor(Color.parseColor("#7FA8B3"));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(8), 0, dp(16));
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollLp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        heardView = new TextView(this);
        heardView.setText("You: ...");
        heardView.setTextSize(18);
        heardView.setTextColor(Color.WHITE);
        heardView.setPadding(0, 0, 0, dp(16));
        box.addView(heardView);

        replyView = new TextView(this);
        replyView.setText("Jarvis: ...");
        replyView.setTextSize(18);
        replyView.setTextColor(CYAN);
        box.addView(replyView);

        scroll.addView(box);
        root.addView(scroll);

        micButton = new Button(this);
        micButton.setText("\uD83C\uDFA4");
        micButton.setTextSize(40);
        micButton.setTextColor(CYAN);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#0D2230"));
        circle.setStroke(dp(3), CYAN);
        micButton.setBackground(circle);
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(120), dp(120));
        micLp.setMargins(0, dp(16), 0, dp(8));
        micLp.gravity = Gravity.CENTER_HORIZONTAL;
        micButton.setLayoutParams(micLp);
        micButton.setOnClickListener(v -> onMicTapped());
        root.addView(micButton);

        langButton = new Button(this);
        langButton.setText("Language: English");
        langButton.setAllCaps(false);
        langButton.setTextColor(CYAN);
        langButton.setBackgroundColor(Color.TRANSPARENT);
        langButton.setOnClickListener(v -> {
            tamil = !tamil;
            langButton.setText(tamil ? "Language: Tamil" : "Language: English");
        });
        root.addView(langButton);

        settingsButton = new Button(this);
        settingsButton.setText("Settings (API key)");
        settingsButton.setAllCaps(false);
        settingsButton.setTextColor(Color.parseColor("#7FA8B3"));
        settingsButton.setBackgroundColor(Color.TRANSPARENT);
        settingsButton.setOnClickListener(v -> {
            keyPanel.setVisibility(keyPanel.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
        });
        root.addView(settingsButton);

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
            prefs().edit().putString("gemini_key", k).apply();
            keyInput.setText("");
            keyInput.setHint("Key saved. Paste to replace");
            keyPanel.setVisibility(View.GONE);
            statusView.setText("Key saved. Tap the mic and speak");
        });
        keyPanel.addView(saveBtn);

        root.addView(keyPanel);

        setContentView(root);
    }

    private void onMicTapped() {
        if (listening) {
            if (recognizer != null) recognizer.stopListening();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                statusView.setText("Mic permission denied");
            }
        }
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.setText("Speech recognition not available on this phone");
            return;
        }
        if (tts != null) tts.stop();

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    listening = true;
                    statusView.setText("Listening...");
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    statusView.setText("Processing...");
                }
                @Override public void onError(int error) {
                    listening = false;
                    statusView.setText("Could not hear (error " + error + "). Tap mic to try again");
                }
                @Override public void onResults(Bundle results) {
                    listening = false;
                    ArrayList<String> list =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) {
                        handleCommand(list.get(0));
                    } else {
                        statusView.setText("Nothing heard. Tap mic to try again");
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> list =
                            partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) {
                        heardView.setText("You: " + list.get(0));
                    }
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tamil ? "ta-IN" : "en-IN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(intent);
    }

    private void handleCommand(String heard) {
        heardView.setText("You: " + heard);
        String t = heard.toLowerCase(Locale.ROOT);

        // Quick local answers (no internet needed)
        if (t.matches(".*\\btime\\b.*") || t.contains("neram")
                || t.contains("\u0BA8\u0BC7\u0BB0\u0BAE\u0BCD") || t.contains("\u0BAE\u0BA3\u0BBF")) {
            showReply("It is " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date()));
            return;
        }
        if (t.matches(".*\\bdate\\b.*") || t.contains("\u0BA4\u0BC7\u0BA4\u0BBF")) {
            showReply("Today is " + new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(new Date()));
            return;
        }

        // Everything else goes to the AI
        final String key = getKey();
        if (key.isEmpty()) {
            showReply("Please save your API key in Settings first.");
            return;
        }

        history.add(new String[]{"user", heard});
        final ArrayList<String[]> snapshot = new ArrayList<>(history);
        statusView.setText("Thinking...");

        new Thread(() -> {
            try {
                final String answer = askGemini(key, snapshot);
                runOnUiThread(() -> {
                    history.add(new String[]{"model", answer});
                    while (history.size() > MAX_HISTORY) {
                        history.remove(0);
                        if (!history.isEmpty()) history.remove(0);
                    }
                    showReply(answer);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    if (!history.isEmpty()) history.remove(history.size() - 1);
                    replyView.setText("Jarvis: (error) " + e.getMessage());
                    statusView.setText("AI request failed. Tap mic to try again");
                });
            }
        }).start();
    }

    private String askGemini(String key, ArrayList<String[]> msgs) throws Exception {
        JSONObject body = new JSONObject();
        body.put("system_instruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT))));
        JSONArray contents = new JSONArray();
        for (String[] m : msgs) {
            contents.put(new JSONObject()
                    .put("role", m[0])
                    .put("parts", new JSONArray().put(new JSONObject().put("text", m[1]))));
        }
        body.put("contents", contents);

        HttpURLConnection c = (HttpURLConnection) new URL(API_URL).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String resp = readAll(is);
        c.disconnect();

        if (code < 200 || code >= 300) {
            String shortResp = resp.length() > 200 ? resp.substring(0, 200) : resp;
            throw new Exception("HTTP " + code + " " + shortResp);
        }

        JSONObject json = new JSONObject(resp);
        JSONArray cands = json.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            throw new Exception("No answer from AI");
        }
        JSONArray parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            sb.append(parts.getJSONObject(i).optString("text", ""));
        }
        String out = sb.toString().trim();
        if (out.isEmpty()) throw new Exception("Empty answer from AI");
        return out;
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void showReply(String reply) {
        String clean = reply.replace("*", "").replace("#", "").replace("`", "").trim();
        replyView.setText("Jarvis: " + clean);
        statusView.setText("Tap the mic and speak");
        speak(clean);
    }

    private boolean hasTamil(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '\u0B80' && ch <= '\u0BFF') return true;
        }
        return false;
    }

    private void speak(String text) {
        if (!ttsReady || tts == null) return;
        boolean ta = hasTamil(text);
        int r = tts.setLanguage(ta ? new Locale("ta", "IN") : new Locale("en", "IN"));
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (ta) {
                statusView.setText("Tamil voice not installed on this phone (text only)");
                return;
            }
            tts.setLanguage(Locale.US);
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            recognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
                               }
