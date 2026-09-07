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

/**
 * StockIT PFE — Extraction structurée d'une étiquette de carton.
 *
 * Pipeline :
 *   1) OCR MLKit local sur la photo de l'étiquette.
 *   2) Envoi du texte brut à Cimpress Gateway (Claude Opus 4) pour extraction JSON :
 *      {
 *        "productName":  "IMPACT 100 MS Stereo USB-C+A",
 *        "quantity":     20,
 *        "articleNumber":"1001421",
 *        "brand":        "EPOS",
 *        "poOnLabel":    "3480"
 *      }
 *   3) Retour d'un objet {@link Label} (Serializable pour Intent extras).
 */
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
        public String error;
    }

    public interface Callback { void onParsed(Label label); }
    public interface Progress { void onStep(String label); }

    public static void parse(final Bitmap photo, final Callback cb, final Progress progress) {
        if (photo == null) { cb.onParsed(errorLabel("empty_photo")); return; }

        new Thread(() -> {
            try {
                if (progress != null) progress.onStep("[1/2] OCR MLKit sur l'étiquette…");
                String ocr = runOcr(photo);
                if (ocr == null || ocr.trim().isEmpty()) {
                    cb.onParsed(errorLabel("OCR vide — texte non détecté sur l'étiquette"));
                    return;
                }
                Log.i(TAG, "OCR chars=" + ocr.length() + " preview=" + preview(ocr, 200));

                if (progress != null) progress.onStep("[2/2] Claude Opus 4 via Cimpress (" + ocr.length() + " chars)…");
                Label lbl = extractViaClaude(ocr);
                lbl.rawOcr = ocr;
                cb.onParsed(lbl);
            } catch (Throwable t) {
                Log.e(TAG, "parser crash", t);
                cb.onParsed(errorLabel("Crash parser : " + t.getClass().getSimpleName() + " — " + t.getMessage()));
            }
        }, "package-label-parser").start();
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
              "Voici le texte OCR d'une ETIQUETTE DE CARTON d'expedition d'un produit informatique. "
            + "Extrais UNIQUEMENT ce JSON strict :\n"
            + "{\n"
            + "  \"productName\":   \"Nom exact et complet du produit (ex: IMPACT 100 MS Stereo USB-C+A)\",\n"
            + "  \"quantity\":       20,\n"
            + "  \"articleNumber\": \"Numero d'article (Art.-No.)\",\n"
            + "  \"brand\":          \"Marque (ex: EPOS)\",\n"
            + "  \"poOnLabel\":      \"Numero PO imprime sur l'etiquette (ex: 3480 ou PO-3480), ou null si absent\"\n"
            + "}\n"
            + "Regles strictes :\n"
            + "- quantity est un ENTIER (regarde QTY / QUANTITY / Qte). Par defaut 1 si absent.\n"
            + "- productName = designation complete, PAS le nom de la marque seule.\n"
            + "- Ne devine PAS : si un champ est absent, mets une chaine vide ou null.\n"
            + "- Reponds UNIQUEMENT le JSON, rien avant, rien apres, pas de markdown.\n\n"
            + "Texte OCR :\n" + ocrText;

        String body = "{\n" +
                "  \"model\": " + jsonQuote(model) + ",\n" +
                "  \"max_tokens\": 400,\n" +
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
            if (lbl.quantity <= 0) lbl.quantity = 1;
        } catch (org.json.JSONException e) {
            Log.w(TAG, "JSON parse fail, raw=" + preview(clean, 300), e);
            lbl.error = "json_parse_fail — raw=" + preview(clean, 120);
        }
    }

    private static String optStrOrNull(org.json.JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, "").trim();
        return v.isEmpty() || "null".equalsIgnoreCase(v) ? null : v;
    }

    // ---------- helpers (identiques à DeliveryNoteParser) ----------
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

    private static Label errorLabel(String err) {
        Label l = new Label(); l.error = err; return l;
    }
}
