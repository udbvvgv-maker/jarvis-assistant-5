package com.jarvis.assistant;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_MIC = 1;
    private static final int CYAN = Color.parseColor("#00E5FF");
    private static final int BG = Color.parseColor("#0A0F1A");

    private TextView statusView;
    private TextView heardView;
    private TextView replyView;
    private Button micButton;
    private Button langButton;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listening = false;
    private boolean tamil = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("en", "IN"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("J.A.R.V.I.S");
        title.setTextSize(28);
        title.setTextColor(CYAN);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.3f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Tap the mic and speak");
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
        micLp.setMargins(0, dp(16), 0, dp(16));
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
        String reply;

        if (t.contains("time") || t.contains("neram")) {
            reply = "It is " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date());
        } else if (t.contains("date") || t.contains("today")) {
            reply = "Today is " + new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(new Date());
        } else if (t.contains("hello") || t.contains("hi ") || t.equals("hi") || t.contains("jarvis")) {
            reply = "Hello. I am online and ready.";
        } else {
            reply = "I heard: " + heard + ". My AI brain is not connected yet.";
        }

        replyView.setText("Jarvis: " + reply);
        statusView.setText("Tap the mic and speak");
        speak(reply);
    }

    private void speak(String text) {
        if (ttsReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
        }
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
