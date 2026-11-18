package com.amit.voiceai;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
    private Button btnSelectModel;

    private File modelFile;

    // File Picker Launcher
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    copyModelToInternalStorage(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        tvUserText = findViewById(R.id.tvUserText);
        tvAiText = findViewById(R.id.tvAiText);
        tvStatus = findViewById(R.id.tvStatus);
        btnMic = findViewById(R.id.btnMic);
        btnSelectModel = findViewById(R.id.btnSelectModel);

        modelFile = new File(getFilesDir(), "gemma_model.task");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.US);
        });

        // Agar model pehle se copy hai, toh direct load karo
        if (modelFile.exists()) {
            initializeAI();
        } else {
            tvStatus.setText("Model not found. Please select a .task file.");
        }

        btnSelectModel.setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));

        btnMic.setOnClickListener(v -> {
            if (llmInference == null) {
                Toast.makeText(this, "AI Model is not loaded yet!", Toast.LENGTH_SHORT).show();
                return;
            }
            checkPermissionAndListen();
        });
    }

    private void copyModelToInternalStorage(Uri sourceUri) {
        tvStatus.setText("Copying model to app storage... Please wait.");
        btnSelectModel.setEnabled(false);

        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(sourceUri);
                 FileOutputStream os = new FileOutputStream(modelFile)) {
                
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
                os.flush();

                runOnUiThread(() -> {
                    tvStatus.setText("Model copied! Loading AI...");
                    initializeAI();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("Copy failed: " + e.getMessage());
                    btnSelectModel.setEnabled(true);
                });
            }
        }).start();
    }

    private void initializeAI() {
        new Thread(() -> {
            try {
                runOnUiThread(() -> tvStatus.setText("Loading AI Model into memory..."));
                
                LlmInferenceOptions options = LlmInferenceOptions.builder()
                        .setModelPath(modelFile.getAbsolutePath())
                        .setMaxTokens(1000)
                        .build();

                llmInference = LlmInference.createFromOptions(this, options);

                runOnUiThread(() -> {
                    tvStatus.setText("AI Ready! Tap mic to speak.");
                    btnSelectModel.setVisibility(View.GONE); // Hide select button once loaded
                    btnMic.setAlpha(1f);
                    btnMic.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("Failed to load: " + e.getMessage()));
            }
        }).start();
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
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }
}
