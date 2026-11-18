package com.amit.voiceai;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_SPEECH = 100;
    private static final int REQ_AUDIO_PERM = 101;

    private LlmInference llmInference;
    private TextToSpeech tts;
    private TextView tvUserText, tvAiText, tvStatus;
    private ImageView btnMic;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        tvUserText = findViewById(R.id.tvUserText);
        tvAiText = findViewById(R.id.tvAiText);
        tvStatus = findViewById(R.id.tvStatus);
        btnMic = findViewById(R.id.btnMic);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
            }
        });

        initializeAI();

        btnMic.setOnClickListener(v -> {
            if (llmInference == null) {
                Toast.makeText(this, "AI Model is still loading...", Toast.LENGTH_SHORT).show();
                return;
            }
            checkPermissionAndListen();
        });
    }

    private void initializeAI() {
        new Thread(() -> {
            try {
                // 1. Target file in internal storage
                File modelFile = new File(getFilesDir(), "gemma2-2b-it-cpu-int4.task");

                // 2. Copy from Assets to Internal Storage if not already done
                if (!modelFile.exists()) {
                    runOnUiThread(() -> tvStatus.setText("Copying model to internal storage..."));
                    copyAssetToFile("gemma2-2b-it-cpu-int4.task", modelFile);
                }

                runOnUiThread(() -> tvStatus.setText("Loading AI Model..."));

                // 3. Initialize LLM
                LlmInferenceOptions options = LlmInferenceOptions.builder()
                        .setModelPath(modelFile.getAbsolutePath())
                        .setMaxTokens(1000)
                        .build();

                llmInference = LlmInference.createFromOptions(this, options);

                runOnUiThread(() -> tvStatus.setText("AI Ready! Tap mic to speak."));
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("Model load failed: " + e.getMessage()));
            }
        }).start();
    }

    private void copyAssetToFile(String assetName, File destFile) throws Exception {
        InputStream is = getAssets().open(assetName);
        FileOutputStream os = new FileOutputStream(destFile);
        byte[] buffer = new byte[8192];
        int length;
        while ((length = is.read(buffer)) > 0) {
            os.write(buffer, 0, length);
        }
        os.close();
        is.close();
    }

    private void checkPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERM);
        } else {
            startListening();
        }
    }

    private void startListening() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...");
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "Speech Recognition not supported", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                String userText = result.get(0);
                tvUserText.setText(userText);
                tvUserText.setVisibility(View.VISIBLE);
                tvAiText.setVisibility(View.GONE);
                tvStatus.setText("AI is thinking...");
                generateAIResponse(userText);
            }
        }
    }

    private void generateAIResponse(String prompt) {
        new Thread(() -> {
            try {
                String formattedPrompt = "<start_of_turn>user\n" + prompt + "<end_of_turn>\n<start_of_turn>model\n";
                String response = llmInference.generateResponse(formattedPrompt);
                runOnUiThread(() -> {
                    tvAiText.setText(response);
                    tvAiText.setVisibility(View.VISIBLE);
                    tvStatus.setText("Tap mic to speak again.");
                    tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, "AI_RESPONSE");
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERM && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
