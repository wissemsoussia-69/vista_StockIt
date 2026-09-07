package com.example.stockit.util;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.stockit.BuildConfig;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class PackageLabelParser {

    private static final String TAG = "PackageLabelParser";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private PackageLabelParser() {}

    public static class Label implements Serializable {
        public String rawOcr;
        public String productName;
        public int    quantity = 1;
        public String articleNumber;
        public String brand;
        public String poOnLabel;
        public String serialNumber;
        public String upc;
        public String error;
    }

    public interface Callback { void onParsed(Label label); }
    public interface Progress { void onStep(String label); }

    public static void parse(final Bitmap photo, final Callback cb, final Progress progress) {
        if (photo == null) { cb.onParsed(errorLabel("empty_photo")); return; }

        new Thread(() -> {
            try {
                if (progress != null) progress.onStep("[1/2] OCR MLKit on label...");
                String ocr = runOcr(photo);
                if (ocr == null || ocr.trim().isEmpty()) {
                    cb.onParsed(errorLabel("OCR empty - no text detected on label"));
                    return;
                }
                Log.i(TAG, "OCR chars=" + ocr.length() + " preview=" + preview(ocr, 200));

                if (progress != null) progress.onStep("[2/2] Claude Opus 4 via Cimpress (" + ocr.length() + " chars)...");
                Label lbl = extractViaClaude(ocr);
                lbl.rawOcr = ocr;
                cb.onParsed(lbl);
            } catch (Throwable t) {
                Log.e(TAG, "parser crash", t);
                cb.onParsed(errorLabel("Parser crash: " + t.getClass().getSimpleName() + " - " + t.getMessage()));
            }
        }, "package-label-parser").start();
    }

    private static String runOcr(Bitmap bmp) {
        try {
            InputImage img = InputImage.fromBitmap(bmp, 0);
            TextRecognizer rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> ref = new AtomicReference<>(null);
            rec.process(img)
                    .addOnSuccessListener(t -> { ref.set(t.getText()); latch.countDown(); })
                    .addOnFailureListener(e -> { Log.w(TAG, "OCR fail", e); latch.countDown(); });
            latch.await(20, TimeUnit.SECONDS);
            return ref.get();
        } catch (Exception e) {
            Log.e(TAG, "OCR ex", e);
            return null;
        }
    }

    private static Label extractViaClaude(String ocrText) {
        Label lbl = new Label();
        String key = BuildConfig.CIMPRESS_GATEWAY_KEY;
        String url = BuildConfig.GATEWAY_URL;
        String model = BuildConfig.CIMPRESS_VISION_MODEL;
        if (key == null || key.isEmpty() || url == null || url.isEmpty()) {
            lbl.error = "cimpress_not_configured";
            return lbl;
        }

                String prompt =
                            "Here is OCR text from a SHIPPING BOX LABEL of an IT product. "
                        + "Extract ONLY this strict JSON:\n"
            + "{\n"
            + "  \"productName\":   \"Exact full product name (e.g. IMPACT 100 MS Stereo USB-C+A)\",\n"
            + "  \"quantity\":       20,\n"
            + "  \"articleNumber\": \"Item number (Art.-No.)\",\n"
            + "  \"brand\":          \"Brand (e.g. EPOS)\",\n"
            + "  \"poOnLabel\":      \"PO number printed on label (e.g. 3480 or PO-3480), or null if missing\",\n"
            + "  \"serialNumber\":   \"Serial number (S/N, Serial No, SN), or null if missing\",\n"
            + "  \"upc\":            \"UPC/EAN/GTIN printed on barcode (8-14 digits), or null if missing\"\n"
            + "}\n"
            + "Strict rules:\n"
            + "- quantity must be an INTEGER (look for QTY / QUANTITY). Default to 1 if missing.\n"
            + "- productName must be the full product designation, NOT only the brand.\n"
            + "- upc must be digits only (8 to 14), barcode format. Ignore non-barcode identifiers.\n"
            + "- DO NOT GUESS: if a field is missing, use empty string or null.\n"
            + "- Return ONLY JSON, nothing before or after, no markdown.\n\n"
            + "OCR text:\n" + ocrText;

        String body = "{\n" +
                "  \"model\": " + jsonQuote(model) + ",\n" +
                "  \"max_tokens\": 500,\n" +
                "  \"messages\": [{\n" +
                "    \"role\": \"user\",\n" +
                "    \"content\": " + jsonQuote(prompt) + "\n" +
                "  }]\n" +
                "}";

        Request req = new Request.Builder()
                .url(url.replaceAll("/$", "") + "/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            String payload = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                Log.w(TAG, "Cimpress HTTP " + resp.code() + " -> " + preview(payload, 300));
                lbl.error = "HTTP " + resp.code();
                return lbl;
            }
            String content = extractOpenAiContent(payload);
            Log.i(TAG, "LLM raw -> " + preview(content, 400));
            fillFromJson(lbl, content);
        } catch (IOException e) {
            Log.e(TAG, "Cimpress IO", e);
            lbl.error = e.getMessage();
        }
        return lbl;
    }

    private static void fillFromJson(Label lbl, String llm) {
        if (llm == null) { lbl.error = "llm_empty"; return; }
        String clean = llm.trim();
        if (clean.startsWith("```")) {
            int nl = clean.indexOf('\n');
            if (nl > 0) clean = clean.substring(nl + 1);
            if (clean.endsWith("```")) clean = clean.substring(0, clean.length() - 3);
            clean = clean.trim();
        }
        try {
            org.json.JSONObject obj = new org.json.JSONObject(clean);
            lbl.productName    = optStrOrNull(obj, "productName");
            lbl.quantity       = obj.optInt("quantity", 1);
            lbl.articleNumber  = optStrOrNull(obj, "articleNumber");
            lbl.brand          = optStrOrNull(obj, "brand");
            lbl.poOnLabel      = optStrOrNull(obj, "poOnLabel");
            lbl.serialNumber   = optStrOrNull(obj, "serialNumber");
            lbl.upc            = sanitizeUpc(optStrOrNull(obj, "upc"));
            if (lbl.quantity <= 0) lbl.quantity = 1;
        } catch (org.json.JSONException e) {
            Log.w(TAG, "JSON parse fail, raw=" + preview(clean, 300), e);
            lbl.error = "json_parse_fail - raw=" + preview(clean, 120);
        }
    }

    private static String sanitizeUpc(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 8 || digits.length() > 14) return null;
        return digits;
    }

    private static String optStrOrNull(org.json.JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, "").trim();
        return v.isEmpty() || "null".equalsIgnoreCase(v) ? null : v;
    }

    private static String extractOpenAiContent(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);

            org.json.JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                org.json.JSONObject first = choices.optJSONObject(0);
                if (first != null) {
                    org.json.JSONObject msg = first.optJSONObject("message");
                    if (msg != null) {
                        String v = contentToString(msg.opt("content"));
                        if (v != null && !v.trim().isEmpty()) return v;
                    }
                }
            }

            String direct = contentToString(root.opt("content"));
            return (direct == null || direct.trim().isEmpty()) ? null : direct;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String contentToString(Object content) {
        if (content == null || content == org.json.JSONObject.NULL) return null;
        if (content instanceof String) return (String) content;
        if (content instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof org.json.JSONObject) {
                    String txt = ((org.json.JSONObject) item).optString("text", "").trim();
                    if (!txt.isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(txt);
                    }
                } else if (item instanceof String) {
                    String txt = ((String) item).trim();
                    if (!txt.isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(txt);
                    }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    private static String jsonQuote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static Label errorLabel(String err) {
        Label l = new Label(); l.error = err; return l;
    }
}
