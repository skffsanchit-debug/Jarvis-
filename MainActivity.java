package com.sanchit.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int SPEECH = 1;
    private static final int PERMISSIONS = 2;
    private static final int MIC = 3;
    private static final String BACKUP_MODEL = "gemini-3.1-flash-lite";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable listenTask = this::listenHandsFree;
    private SpeechRecognizer recognizer;
    private Button handsFreeButton;
    private boolean handsFree;
    private boolean resumed;
    private boolean listening;
    private long followUpUntil;
    private static final String DEFAULT_MODEL = "gemini-flash-latest";
    private final JSONArray history = new JSONArray();
    private SharedPreferences prefs;
    private boolean busy;
    private TextToSpeech tts;
    private TextView screen;
    private String pendingName;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 48, 48, 48);

        screen = new TextView(this);
        screen.setTextSize(20);
        screen.setGravity(Gravity.CENTER);
        screen.setText("Jarvis ready. Pehle Settings mein API key daalo.\n\nBolo:\n\"Rahul ko call karo\"\n\"YouTube kholo\"\n"
                + "\"Alarm 6:30 am\"\n\"Torch on\"\n\"Time kya hua\"\n\"Search cricket score\"");

        Button mic = new Button(this);
        mic.setText("Bolo");
        mic.setTextSize(24);
        mic.setOnClickListener(v -> listen());

        layout.addView(screen);
        layout.addView(mic);

        handsFreeButton = new Button(this);
        handsFreeButton.setText("Hands-free: OFF");
        handsFreeButton.setOnClickListener(v -> setHandsFree(!handsFree));
        layout.addView(handsFreeButton);

        Button voice = new Button(this);
        voice.setText("Voice badlo");
        voice.setOnClickListener(v -> chooseVoice());
        layout.addView(voice);

        Button settings = new Button(this);
        settings.setText("Settings");
        settings.setOnClickListener(v -> showSettings());
        layout.addView(settings);
        setContentView(layout);

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(Locale.UK);
            tts.setPitch(0.9f);
            String saved = prefs.getString("voice", "");
            for (Voice v : voices()) {
                if (v.getName().equals(saved)) tts.setVoice(v);
            }
        });

        requestMissingPermissions();
    }

    private void setHandsFree(boolean on) {
        if (on && !granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
            return;
        }
        if (on && !SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Is phone pe hands free voice input nahi mila.");
            return;
        }
        handsFree = on;
        handsFreeButton.setText(on ? "Hands-free: ON" : "Hands-free: OFF");
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            say("Hands free on. Say Jarvis, followed by your command.");
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            stopListening();
        }
    }

    private void scheduleListen(long delay) {
        handler.removeCallbacks(listenTask);
        if (handsFree && resumed) handler.postDelayed(listenTask, delay);
    }

    private void stopListening() {
        handler.removeCallbacks(listenTask);
        listening = false;
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private void listenHandsFree() {
        if (!handsFree || !resumed || listening) return;
        // Wait so Jarvis does not hear its own voice.
        if (busy || tts.isSpeaking()) {
            scheduleListen(500);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onResults(Bundle results) {
                    listening = false;
                    ArrayList<String> heard =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (heard != null && !heard.isEmpty()) wake(heard.get(0));
                    scheduleListen(800);
                }
                @Override public void onError(int error) {
                    stopListening();
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        setHandsFree(false);
                        screen.setText("Mic permission check karo.");
                    } else {
                        scheduleListen(1000);
                    }
                }
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float level) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onPartialResults(Bundle partial) { }
                @Override public void onEvent(int type, Bundle params) { }
            });
        }
        listening = true;
        recognizer.startListening(speechIntent());
    }

    private void wake(String heard) {
        String lower = heard.toLowerCase(Locale.ROOT);
        boolean named = lower.contains("jarvis");
        if (!named && System.currentTimeMillis() > followUpUntil) return;
        followUpUntil = 0;
        String command = lower.replaceAll("\\b(hey |hi |ok )?jarvis\\b", " ").trim();
        if (command.isEmpty()) {
            followUpUntil = System.currentTimeMillis() + 15000;
            say("Yes, Sir?");
        } else if (command.equals("stop") || command.equals("bas") || command.equals("chup")) {
            say("Very well, Sir.");
        } else if (prefs.getString("key", "").isEmpty()) {
            handle(command);
        } else {
            askAi(command);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        scheduleListen(1000);
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopListening();
        super.onPause();
    }

    private Intent speechIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        return i;
    }

    private void listen() {
        tts.stop();
        try {
            startActivityForResult(speechIntent(), SPEECH);
        } catch (Exception e) {
            say("Voice input nahi mila. Google app install ya enable karo.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SPEECH || result != RESULT_OK || data == null) return;
        ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        if (prefs.getString("key", "").isEmpty()) handle(heard.get(0).toLowerCase(Locale.ROOT));
        else askAi(heard.get(0));
    }

    private void handle(String text) {
        List<String> words = Arrays.asList(text.split("\\s+"));
        if (words.contains("torch") || words.contains("flashlight")) {
            torch(!(words.contains("off") || words.contains("band")));
        } else if (words.contains("alarm")) {
            alarm(text);
        } else if (words.contains("open") || words.contains("kholo")) {
            openApp(strip(text, "jarvis", "open", "kholo", "karo", "app", "please"));
        } else if (words.contains("search")) {
            String q = strip(text, "jarvis", "search", "karo", "please");
            start(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))), "Search kar raha hoon");
        } else if (words.contains("call") || words.contains("phone")) {
            call(strip(text, "jarvis", "call", "phone", "ko", "karo", "kar", "do", "lagao", "please", "to"));
        } else if (words.contains("time") || words.contains("baje")) {
            say("Abhi time hai " + new SimpleDateFormat("h:mm a", Locale.US).format(new Date()));
        } else if (words.contains("date") || words.contains("tarikh")) {
            say("Aaj hai " + new SimpleDateFormat("d MMMM yyyy", Locale.US).format(new Date()));
        } else if (words.contains("hello") || words.contains("hi")) {
            say("Hello, main Jarvis hoon. Bolo kya karna hai.");
        } else {
            say("Ye command mujhe abhi nahi aati: " + text);
        }
    }

    private String strip(String text, String... remove) {
        List<String> skip = Arrays.asList(remove);
        StringBuilder out = new StringBuilder();
        for (String w : text.split("\\s+")) {
            if (!skip.contains(w)) out.append(w).append(' ');
        }
        return out.toString().trim();
    }

    private void torch(boolean on) {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    cm.setTorchMode(id, on);
                    say(on ? "Torch on" : "Torch off");
                    return;
                }
            }
            say("Is phone mein torch nahi mili");
        } catch (Exception e) {
            say("Torch control nahi ho paya");
        }
    }

    private void alarm(String text) {
        Matcher m = Pattern.compile("(\\d{1,2})(?:[:. ](\\d{2}))?").matcher(text);
        if (!m.find()) {
            say("Alarm ka time bhi bolo, jaise alarm 6 30 am");
            return;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        boolean pm = text.matches(".*(p\\.m|\\bpm\\b|shaam|raat).*");
        boolean am = text.matches(".*(a\\.m|\\bam\\b|subah).*");
        if (pm && hour < 12) hour += 12;
        if (am && hour == 12) hour = 0;
        if (hour > 23 || minute > 59) {
            say("Ye time samajh nahi aaya");
            return;
        }
        setAlarm(hour, minute);
    }

    private void setAlarm(int hour, int minute) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute);
        start(i, String.format(Locale.US, "Alarm %d:%02d", hour, minute));
    }

    private void openApp(String name) {
        if (name.isEmpty()) {
            say("Kaunsa app kholna hai?");
            return;
        }
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String wanted = name.replace(" ", "");
        String partial = null;
        for (ResolveInfo app : pm.queryIntentActivities(main, 0)) {
            String label = app.loadLabel(pm).toString().toLowerCase(Locale.ROOT).replace(" ", "");
            if (label.equals(wanted)) {
                start(pm.getLaunchIntentForPackage(app.activityInfo.packageName), name + " khol raha hoon");
                return;
            }
            if (partial == null && label.contains(wanted)) partial = app.activityInfo.packageName;
        }
        if (partial != null) start(pm.getLaunchIntentForPackage(partial), name + " khol raha hoon");
        else say(name + " app nahi mila");
    }

    private void requestMissingPermissions() {
        if (!granted(Manifest.permission.READ_CONTACTS) || !granted(Manifest.permission.CALL_PHONE)) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE}, PERMISSIONS);
        }
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void call(String spoken) {
        String name = spoken.toLowerCase(Locale.ROOT).trim();
        if (name.isEmpty()) {
            say("Kisko call karna hai?");
            return;
        }
        if (!granted(Manifest.permission.READ_CONTACTS) || !granted(Manifest.permission.CALL_PHONE)) {
            pendingName = name;
            requestMissingPermissions();
            return;
        }
        Map<String, String> exact = new LinkedHashMap<>();
        Map<String, String> partial = new LinkedHashMap<>();
        String[] columns = {Phone.DISPLAY_NAME, Phone.NUMBER};
        try (Cursor c = getContentResolver().query(Phone.CONTENT_URI, columns, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String contact = c.getString(0);
                String number = c.getString(1);
                if (contact == null || number == null) continue;
                String lower = contact.toLowerCase(Locale.ROOT).trim();
                if (lower.equals(name)) exact.putIfAbsent(contact, number);
                else if (lower.contains(name)) partial.putIfAbsent(contact, number);
            }
        }
        Map<String, String> matches = exact.isEmpty() ? partial : exact;
        if (matches.isEmpty()) {
            say(name + " contacts mein nahi mila");
        } else if (matches.size() > 1) {
            // Never guess who to ring.
            say("Ek se zyada contact mile: " + String.join(", ", matches.keySet())
                    + ". Poora naam bolo.");
        } else {
            Map.Entry<String, String> hit = matches.entrySet().iterator().next();
            start(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(hit.getValue()))),
                    "Calling " + hit.getKey());
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == MIC) {
            if (granted(Manifest.permission.RECORD_AUDIO)) setHandsFree(true);
            else say("Mic permission ke bina hands free nahi chalega");
            return;
        }
        if (request != PERMISSIONS || pendingName == null) return;
        String name = pendingName;
        pendingName = null;
        if (granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.CALL_PHONE)) {
            call(name);
        } else {
            say("Contacts aur phone permission ke bina call nahi ho sakti");
        }
    }

    private void askAi(String text) {
        if (busy) return;
        busy = true;
        screen.setText("Aapne kaha: " + text + "\n\nSoch raha hoon...");
        String key = prefs.getString("key", "");
        String model = prefs.getString("model", DEFAULT_MODEL);
        Voice voice = tts.getVoice();
        boolean indian = voice != null && (voice.getLocale().getCountry().equals("IN"));
        String language = indian ? "casual Hinglish written in Latin letters" : "simple, clear English";
        String system = "You are Jarvis, a personal voice assistant: intelligent, calm, loyal, with dry "
                + "British wit. Call the user Sir. The user may speak Hindi, Hinglish or English. Reply in "
                + language + ", in one to three short spoken sentences, no markdown or emojis. "
                + "Current date and time: " + new Date() + ". "
                + "Answer with only a JSON object with keys: say (string), action (one of none, call, "
                + "open, alarm, torch, search), arg (call: contact name in Latin letters exactly as the "
                + "user said it; open: app name; search: query; torch: on or off), hour (0-23) and "
                + "minute (0-59) for alarm. Use an action only when the user clearly asks for it now. "
                + "If details are missing, use none and ask. Never claim you can do other things on "
                + "the phone.";
        JSONArray contents = new JSONArray();
        JSONObject userTurn;
        String body;
        try {
            for (int i = 0; i < history.length(); i++) contents.put(history.get(i));
            userTurn = turn("user", text);
            contents.put(userTurn);
            body = new JSONObject()
                    .put("system_instruction", new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text", system))))
                    .put("contents", contents)
                    .put("generationConfig", new JSONObject().put("responseMimeType", "application/json"))
                    .toString();
        } catch (Exception e) {
            busy = false;
            say("Request nahi ban payi");
            return;
        }
        new Thread(() -> {
            try {
                String answer = postWithRetry(model, key, body);
                runOnUiThread(() -> finishAi(userTurn, answer));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    if (!isDestroyed()) say("AI se baat nahi ho payi. " + e.getMessage());
                });
            }
        }).start();
    }

    private JSONObject turn(String role, String text) throws Exception {
        return new JSONObject().put("role", role)
                .put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }

    private String postWithRetry(String model, String key, String body) throws Exception {
        try {
            return post(model, key, body);
        } catch (BusyException first) {
            Thread.sleep(2000);
            try {
                return post(model, key, body);
            } catch (BusyException second) {
                return post(BACKUP_MODEL, key, body);
            }
        }
    }

    private static class BusyException extends Exception {
        BusyException() {
            super("Google AI abhi busy hai, thodi der baad try karo.");
        }
    }

    private String post(String model, String key, String body) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + Uri.encode(model) + ":generateContent");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(45000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 429) throw new Exception("Free limit khatam, thodi der baad try karo.");
            if (code == 500 || code == 503) throw new BusyException();
            if (code != 200) throw new Exception("Error " + code + ". API key aur model name check karo.");
            String raw;
            try (InputStream in = c.getInputStream();
                 Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
                raw = s.hasNext() ? s.next() : "";
            }
            JSONArray parts = new JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (!part.optBoolean("thought")) text.append(part.optString("text"));
            }
            return text.toString();
        } finally {
            c.disconnect();
        }
    }

    private void finishAi(JSONObject userTurn, String answer) {
        busy = false;
        if (isDestroyed()) return;
        try {
            JSONObject reply = new JSONObject(answer);
            history.put(userTurn);
            history.put(turn("model", answer));
            while (history.length() > 12) history.remove(0);
            String words = reply.optString("say");
            String arg = reply.optString("arg");
            followUpUntil = System.currentTimeMillis() + 30000;
            say(words);
            switch (reply.optString("action")) {
                case "call":
                    call(arg);
                    break;
                case "open":
                    openApp(arg.toLowerCase(Locale.ROOT));
                    break;
                case "torch":
                    torch(!arg.equalsIgnoreCase("off"));
                    break;
                case "search":
                    start(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=" + Uri.encode(arg))), words);
                    break;
                case "alarm":
                    int hour = reply.optInt("hour", -1);
                    int minute = reply.optInt("minute", 0);
                    if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) setAlarm(hour, minute);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            say("Jawab samajh nahi aaya, dobara bolo.");
        }
    }

    private List<Voice> voices() {
        List<Voice> list = new ArrayList<>();
        try {
            for (Voice v : tts.getVoices()) {
                String lang = v.getLocale().getLanguage();
                if (lang.equals("en") || lang.equals("hi")) list.add(v);
            }
        } catch (Exception ignored) {
            // Some speech engines do not list voices.
        }
        list.sort((a, b) -> {
            int ga = a.getLocale().getCountry().equals("GB") ? 0 : 1;
            int gb = b.getLocale().getCountry().equals("GB") ? 0 : 1;
            return ga != gb ? ga - gb : a.getName().compareTo(b.getName());
        });
        return list;
    }

    private void chooseVoice() {
        List<Voice> list = voices();
        if (list.isEmpty()) {
            say("Koi aur voice nahi mili. Phone ke text to speech settings check karo.");
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            Voice v = list.get(i);
            names[i] = v.getLocale().getDisplayName() + "\n" + v.getName();
        }
        new AlertDialog.Builder(this).setTitle("Voice chuno").setItems(names, (d, which) -> {
            Voice v = list.get(which);
            tts.setVoice(v);
            prefs.edit().putString("voice", v.getName()).apply();
            say("At your service, Sir.");
        }).show();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);
        EditText key = new EditText(this);
        key.setHint("Gemini API key");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(prefs.getString("key", ""));
        EditText model = new EditText(this);
        model.setHint("Model name");
        model.setText(prefs.getString("model", DEFAULT_MODEL));
        box.addView(key);
        box.addView(model);
        new AlertDialog.Builder(this).setTitle("Settings").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String m = model.getText().toString().trim();
                    prefs.edit().putString("key", key.getText().toString().trim())
                            .putString("model", m.isEmpty() ? DEFAULT_MODEL : m).apply();
                    say("Settings saved, Sir.");
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void start(Intent intent, String message) {
        try {
            say(message);
            startActivity(intent);
        } catch (Exception e) {
            say("Ye kaam is phone pe nahi ho paya");
        }
    }

    private void say(String message) {
        screen.setText(message);
        stopListening();
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
        scheduleListen(1500);
    }

    @Override
    protected void onDestroy() {
        stopListening();
        tts.shutdown();
        super.onDestroy();
    }
}
