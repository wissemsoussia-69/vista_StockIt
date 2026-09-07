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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * StockIT PFE — Extraction structurée d'une facture / bon de livraison.
 *
 * Pipeline :
 *   1) OCR MLKit local sur la photo.
 *   2) Envoi du texte brut à Cimpress Gateway (Claude Opus 4) pour extraction JSON :
 *      {
 *        "supplier": "...",
 *        "purchaseOrders": [
 *          { "number": "PO-1234", "description": "1 phrase résumant les articles" },
 *          ...
 *        ]
 *      }
 *   3) Retour d'un objet {@link ParsedNote} avec la LISTE des POs détectés.
 */
public final class DeliveryNoteParser {

    private static final String TAG = "DeliveryNoteParser";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private DeliveryNoteParser() {}

    /** Un bloc PO extrait de la facture. Serializable pour transit via Intent extras. */
    public static class POBlock implements Serializable {
        public final String number;      // ex "PO-1234"
        public final String description; // ex "Écrans Dell 24", 5 unités"
        public POBlock(String n, String d) { number = n; description = d; }
    }

    public static class ParsedNote implements Serializable {
        public String rawOcr;
        public String supplier;
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
                if (progress != null) progress.onStep("[1/3] OCR MLKit…");
                String ocr = runOcr(photo);
                if (ocr == null || ocr.trim().isEmpty()) {
                    cb.onParsed(errorNote("OCR vide — le texte n'a pas été détecté", null));
                    return;
                }
                Log.i(TAG, "OCR chars=" + ocr.length() + " preview=" + preview(ocr, 200));

                if (progress != null) progress.onStep("[2/3] Claude Opus 4 via Cimpress (" + ocr.length() + " chars)…");
                ParsedNote note = extractViaClaude(ocr);
                note.rawOcr = ocr;
                cb.onParsed(note);
            } catch (Throwable t) {
                Log.e(TAG, "parser crash", t);
                cb.onParsed(errorNote("Crash parser : " + t.getClass().getSimpleName() + " — " + t.getMessage(), null));
            }
        }, "delivery-note-parser").start();
    }

    // ---------- OCR MLKit ----------
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

    // ---------- Claude Opus 4 via Cimpress ----------
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
              "Voici le texte OCR d'une facture ou bon de livraison. "
            + "Elle peut contenir PLUSIEURS numéros de PO (Purchase Order, BL, commande...). "
            + "Extrais UNIQUEMENT un JSON strict avec cette structure exacte :\n"
            + "{\n"
            + "  \"supplier\": \"nom du fournisseur/expéditeur\",\n"
            + "  \"purchaseOrders\": [\n"
            + "    { \"number\": \"PO-1234\", \"description\": \"1 phrase courte résumant les articles associés à ce PO\" }\n"
            + "  ]\n"
            + "}\n"
            + "Règles strictes :\n"
            + "- Détecte TOUS les numéros de PO/BL/commande présents.\n"
            + "- Pour CHAQUE PO, résume en 1 phrase (max 15 mots) le ou les articles rattachés.\n"
            + "- Si aucun PO trouvé, renvoie purchaseOrders: [].\n"
            + "- Réponds UNIQUEMENT le JSON, rien avant, rien après, pas de balise markdown.\n\n"
            + "Texte OCR :\n" + ocrText;

        String body = "{\n" +
                "  \"model\": " + jsonQuote(model) + ",\n" +
                "  \"max_tokens\": 1200,\n" +
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
                note.supplier = obj.getString("supplier");
            }
            if (obj.has("purchaseOrders")) {
                org.json.JSONArray arr = obj.getJSONArray("purchaseOrders");
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject po = arr.getJSONObject(i);
                    String number = po.optString("number", "").trim();
                    String description = po.optString("description", "").trim();
                    if (!number.isEmpty()) note.purchaseOrders.add(new POBlock(number, description));
                }
            }
        } catch (org.json.JSONException e) {
            Log.w(TAG, "JSON parse fail, raw=" + preview(clean, 300), e);
            note.error = "json_parse_fail — raw=" + preview(clean, 120);
        }
    }

    /** Utilitaire public : extrait la partie numérique d'un poNumber (ex "PO-12" → 12). */
    public static Integer extractIntFromPoNumber(String s) {
        if (s == null) return null;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) d.append(s.charAt(i));
        if (d.length() == 0) return null;
        try { return Integer.parseInt(d.toString()); } catch (NumberFormatException e) { return null; }
    }

    // ---------- helpers ----------
    private static String extractOpenAiContent(String json) {
        if (json == null) return null;
        int c = json.indexOf("\"content\"");
        if (c < 0) return null;
        int colon = json.indexOf(':', c);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default:  sb.append(n);
                }
            } else if (ch == '"') {
                return sb.toString();
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
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
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static ParsedNote errorNote(String err, String rawOcr) {
        ParsedNote n = new ParsedNote(); n.error = err; n.rawOcr = rawOcr; return n;
    }
}
