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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class DeliveryNoteParser {

    private static final String TAG = "DeliveryNoteParser";
        private static final String[] KNOWN_BRANDS = {
            "EPOS", "DELL", "HP", "LENOVO", "LOGITECH", "JABRA", "SENNHEISER", "ACT"
        };
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private DeliveryNoteParser() {}

    public static class POBlock implements Serializable {
        public final String number;      // ex "PO-1234"
        public final String description; // e.g. "Dell 24-inch screens", 5 units
        public String brand;             // e.g. "Dell" - used as Device Name in Jira Assets
        public String model;             // e.g. "Dell Pro Micro QCM 1250" - Asset Model in Jira
        public java.util.List<String> serialNumbers = new java.util.ArrayList<>();
        public POBlock(String n, String d) { number = n; description = d; }
    }

    public static class ParsedNote implements Serializable {
        public String rawOcr;
        public String supplier;
        public String invoiceNumber;   // e.g. "INV-2024-001" - Invoice Number field in Jira
        public String invoiceDate;     // e.g. "2024-08-14" - Invoice Date field in Jira
        public final List<POBlock> purchaseOrders = new ArrayList<>();
        public String error;
    }

    public interface Callback { void onParsed(ParsedNote note); }
    public interface Progress { void onStep(String label); }

    public static void parse(final Bitmap photo, final Callback cb) {
        parse(photo, cb, null);
    }

    public static void parse(final Bitmap photo, final Callback cb, final Progress progress) {
        if (photo == null) { cb.onParsed(errorNote("empty_photo", null)); return; }

        new Thread(() -> {
            try {
                if (progress != null) progress.onStep("[1/3] OCR MLKit...");
                String ocr = runOcr(photo);
                if (ocr == null || ocr.trim().isEmpty()) {
                    cb.onParsed(errorNote("OCR empty - no text detected", null));
                    return;
                }
                Log.i(TAG, "OCR chars=" + ocr.length() + " preview=" + preview(ocr, 200));

                if (progress != null) progress.onStep("[2/3] Claude Opus 4 via Cimpress (" + ocr.length() + " chars)...");
                ParsedNote note = extractViaClaude(ocr);
                note.rawOcr = ocr;
                cb.onParsed(note);
            } catch (Throwable t) {
                Log.e(TAG, "parser crash", t);
                cb.onParsed(errorNote("Parser crash: " + t.getClass().getSimpleName() + " - " + t.getMessage(), null));
            }
        }, "delivery-note-parser").start();
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

    private static ParsedNote extractViaClaude(String ocrText) {
        ParsedNote note = new ParsedNote();
        String key = BuildConfig.CIMPRESS_GATEWAY_KEY;
        String url = BuildConfig.GATEWAY_URL;
        String model = BuildConfig.CIMPRESS_VISION_MODEL;
        if (key == null || key.isEmpty() || url == null || url.isEmpty()) {
            note.error = "cimpress_not_configured";
            return note;
        }

                String prompt =
                            "Here is OCR text from an invoice or delivery note. "
                        + "It can contain MULTIPLE PO numbers (Purchase Order, delivery note, order...). "
                        + "Extract ONLY strict JSON with this exact structure:\n"
            + "{\n"
            + "  \"supplier\":      \"supplier name: Lactech or ACT (only these values), else null\",\n"
            + "  \"invoiceNumber\": \"invoice number (Invoice # / Bill #)\",\n"
            + "  \"invoiceDate\":   \"invoice date in YYYY-MM-DD if possible, else as printed\",\n"
            + "  \"purchaseOrders\": [\n"
            + "    {\n"
            + "      \"number\":        \"PO-1234\",\n"
            + "      \"description\":   \"complete article line summary including quantity/reference when visible\",\n"
            + "      \"brand\":         \"product brand (e.g. Dell, HP, Lenovo) or null\",\n"
            + "      \"model\":         \"exact model (e.g. Dell Pro Micro QCM 1250) or null\",\n"
            + "      \"serialNumbers\": [\"12HVTC4\", \"12HVTC5\"]\n"
            + "    }\n"
            + "  ]\n"
            + "}\n"
            + "Strict rules:\n"
            + "- supplier must be exactly \"Lactech\" or \"ACT\" when detected; otherwise null.\n"
            + "- Detect ALL PO/delivery/order numbers present.\n"
            + "- For EACH PO, extract brand, model, and COMPLETE list of serial numbers / service tags (S/N, SN, Service Tag).\n"
            + "- serialNumbers must be a JSON array, even empty (`[]`) when none are detected.\n"
            + "- Keep invoiceNumber and invoiceDate when present; do not drop them.\n"
            + "- If no PO is found, return purchaseOrders: [].\n"
            + "- If a field is missing, use null or empty array. DO NOT GUESS.\n"
            + "- Return ONLY JSON, nothing before or after, no markdown fences.\n\n"
            + "OCR text:\n" + ocrText;

        String body = "{\n" +
                "  \"model\": " + jsonQuote(model) + ",\n" +
                "  \"max_tokens\": 2000,\n" +
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
                note.error = "HTTP " + resp.code();
                return note;
            }
            String content = extractOpenAiContent(payload);
            Log.i(TAG, "LLM raw -> " + preview(content, 400));
            fillFromJson(note, content);
            enrichWithOcrFallback(note, ocrText);
        } catch (IOException e) {
            Log.e(TAG, "Cimpress IO", e);
            note.error = e.getMessage();
        }
        return note;
    }

    private static void fillFromJson(ParsedNote note, String llm) {
        if (llm == null) { note.error = "llm_empty"; return; }
        String clean = llm.trim();
        if (clean.startsWith("```")) {
            int nl = clean.indexOf('\n');
            if (nl > 0) clean = clean.substring(nl + 1);
            if (clean.endsWith("```")) clean = clean.substring(0, clean.length() - 3);
            clean = clean.trim();
        }
        try {
            org.json.JSONObject obj = new org.json.JSONObject(clean);
            if (obj.has("supplier") && !obj.isNull("supplier")) {
                note.supplier = normalizeSupplier(obj.getString("supplier"));
            }
            if (obj.has("invoiceNumber") && !obj.isNull("invoiceNumber")) {
                String v = obj.optString("invoiceNumber", "").trim();
                if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) note.invoiceNumber = v;
            }
            if (obj.has("invoiceDate") && !obj.isNull("invoiceDate")) {
                String v = obj.optString("invoiceDate", "").trim();
                if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) note.invoiceDate = v;
            }
            if (obj.has("purchaseOrders")) {
                org.json.JSONArray arr = obj.getJSONArray("purchaseOrders");
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject po = arr.getJSONObject(i);
                    String number = po.optString("number", "").trim();
                    String description = po.optString("description", "").trim();
                    if (number.isEmpty()) continue;
                    POBlock block = new POBlock(number, description);
                    String brand = po.optString("brand", "").trim();
                    String model = po.optString("model", "").trim();
                    if (!brand.isEmpty() && !"null".equalsIgnoreCase(brand)) block.brand = normalizeBrand(brand);
                    if (!model.isEmpty() && !"null".equalsIgnoreCase(model)) block.model = model;
                    if (po.has("serialNumbers") && !po.isNull("serialNumbers")) {
                        org.json.JSONArray sns = po.optJSONArray("serialNumbers");
                        if (sns != null) {
                            for (int j = 0; j < sns.length(); j++) {
                                String sn = sns.optString(j, "").trim();
                                String cleanSn = sanitizeSerial(sn);
                                if (cleanSn != null) block.serialNumbers.add(cleanSn);
                            }
                        }
                    }
                    note.purchaseOrders.add(block);
                }
            }
        } catch (org.json.JSONException e) {
            Log.w(TAG, "JSON parse fail, raw=" + preview(clean, 300), e);
            note.error = "json_parse_fail - raw=" + preview(clean, 120);
        }
    }

    private static void enrichWithOcrFallback(ParsedNote note, String ocrText) {
        if (note == null) return;

        if (note.supplier == null || note.supplier.trim().isEmpty()) {
            note.supplier = normalizeSupplier(ocrText);
        }
        if (note.invoiceNumber == null || note.invoiceNumber.trim().isEmpty()) {
            note.invoiceNumber = extractInvoiceNumber(ocrText);
        }
        if (note.invoiceDate == null || note.invoiceDate.trim().isEmpty()) {
            note.invoiceDate = extractInvoiceDate(ocrText);
        }

        if (note.purchaseOrders.isEmpty()) {
            for (String poNum : extractPoCandidates(ocrText)) {
                POBlock b = new POBlock(poNum, "");
                b.brand = inferBrand(ocrText);
                note.purchaseOrders.add(b);
            }
        }

        for (POBlock block : note.purchaseOrders) {
            if (block == null) continue;
            if (block.brand == null || block.brand.trim().isEmpty()) {
                block.brand = inferBrand((block.model == null ? "" : block.model) + " "
                        + (block.description == null ? "" : block.description) + " "
                        + (ocrText == null ? "" : ocrText));
            }
            LinkedHashSet<String> uniq = new LinkedHashSet<>();
            for (String sn : block.serialNumbers) {
                String clean = sanitizeSerial(sn);
                if (clean != null) uniq.add(clean);
            }
            block.serialNumbers.clear();
            block.serialNumbers.addAll(uniq);
        }
    }

    private static String normalizeSupplier(String input) {
        if (input == null) return null;
        String u = input.toUpperCase(Locale.ROOT);
        if (u.contains("LACTECH") || u.contains("LAC TECH")) return "Lactech";
        if (Pattern.compile("\\bACT\\b", Pattern.CASE_INSENSITIVE).matcher(input).find()) return "ACT";
        return null;
    }

    private static String normalizeBrand(String input) {
        if (input == null) return null;
        String t = input.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) return null;
        for (String b : KNOWN_BRANDS) {
            if (b.equalsIgnoreCase(t)) return b;
        }
        return t;
    }

    private static String inferBrand(String text) {
        if (text == null) return null;
        String u = text.toUpperCase(Locale.ROOT);
        if (u.contains("LACTECH") || u.contains("LAC TECH")) return "Lactech";
        for (String b : KNOWN_BRANDS) {
            if (u.contains(b)) return b;
        }
        return null;
    }

    private static String sanitizeSerial(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if ("null".equalsIgnoreCase(s)) return null;
        if ("none".equalsIgnoreCase(s)) return null;
        if ("none entered".equalsIgnoreCase(s)) return null;
        return s;
    }

    private static String extractInvoiceNumber(String text) {
        if (text == null) return null;
        Pattern p = Pattern.compile("(?i)(?:invoice|bill|facture)\\s*(?:no|number|n[\\u00b0o]|#)?\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-/]{3,})");
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        String v = m.group(1);
        return v == null ? null : v.trim();
    }

    private static String extractInvoiceDate(String text) {
        if (text == null) return null;
        Pattern p = Pattern.compile("\\b(\\d{4}[-/]\\d{2}[-/]\\d{2}|\\d{2}[-/]\\d{2}[-/]\\d{4})\\b");
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        return m.group(1);
    }

    private static List<String> extractPoCandidates(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?i)\\bPO[- ]?\\d{3,}\\b").matcher(text);
        while (m.find()) {
            String v = m.group();
            if (v != null && !v.trim().isEmpty()) uniq.add(v.trim().replaceAll("\\s+", "-"));
        }
        out.addAll(uniq);
        return out;
    }

    public static Integer extractIntFromPoNumber(String s) {
        if (s == null) return null;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) d.append(s.charAt(i));
        if (d.length() == 0) return null;
        try { return Integer.parseInt(d.toString()); } catch (NumberFormatException e) { return null; }
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

    private static ParsedNote errorNote(String err, String rawOcr) {
        ParsedNote n = new ParsedNote(); n.error = err; n.rawOcr = rawOcr; return n;
    }
}
