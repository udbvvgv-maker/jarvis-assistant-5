package com.jarvis.assistant;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView text = new TextView(this);
        text.setText("JARVIS online");
        text.setTextSize(28);
        text.setTextColor(Color.parseColor("#00E5FF"));
        text.setGravity(Gravity.CENTER);
        text.setBackgroundColor(Color.parseColor("#0A0F1A"));

        setContentView(text);
    }
}
