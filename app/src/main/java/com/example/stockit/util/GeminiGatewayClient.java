package com.example.stockit.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.example.stockit.BuildConfig;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * StockIT PFE — Passerelle IA de reconnaissance visuelle.
 *
 * Stratégie en cascade (auto-fallback) :
 *   1. Cimpress Gateway (Vistaprint) — endpoint OpenAI-compatible
 *      https://gateway.ai.cimpress.io/v1/chat/completions
 *      Auth: Authorization: Bearer $CIMPRESS_GATEWAY_KEY
 *      Modèle: $CIMPRESS_VISION_MODEL (défaut : @Anthropic/eu.anthropic.claude-opus-4-8)
 *   2. MLKit image labeling (OFFLINE, sans réseau) — filet de sécurité ultime.
 *
 * TM-01 / TM-26 : les fournisseurs tiers hors Vista (Portkey, Google Gemini,
 * Hugging Face) et leurs clés ont été retirés de l'app.
 *
 * Sortie : nom court FR (Clavier, Souris, Écran, ...), .trim() et max 2 mots.
 */
public final class GeminiGatewayClient {

    private static final String TAG = "GeminiGateway";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType JPEG = MediaType.get("image/jpeg");

    private static final String PROMPT =
            "Renvoie UNIQUEMENT le nom précis et court de l'objet informatique principal "
          + "(ex: 'Clavier', 'Souris', 'Écran'). Pas de phrase, pas de catégorie générale "
          + "comme 'Objet IT', maximum deux mots. Réponds en français.";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build();

    private GeminiGatewayClient() {}

    public interface Callback {
        /** name est trim() et FR ; error != null si tout a échoué. */
        void onResult(String name, String error);
    }

    public static void identify(final byte[] jpegBytes, final Callback cb) {
        if (jpegBytes == null || jpegBytes.length == 0) {
            cb.onResult(null, "empty_image");
            return;
        }

        new Thread(() -> {
            StringBuilder trace = new StringBuilder();

            // 1) Cimpress Gateway (Vistaprint — passerelle officielle PFE)
            String cimKey   = BuildConfig.CIMPRESS_GATEWAY_KEY;
            String cimUrl   = BuildConfig.GATEWAY_URL;
            String cimModel = BuildConfig.CIMPRESS_VISION_MODEL;
            if (!TextUtils.isEmpty(cimKey) && !TextUtils.isEmpty(cimUrl)) {
                String r = tryCimpress(jpegBytes, cimUrl, cimKey, cimModel, trace);
                if (r != null) { cb.onResult(clean(r), null); return; }
            } else {
                trace.append("Cimpress key missing; ");
            }

            // TM-01 / TM-26 : plus aucun égress direct vers Portkey / Google Gemini /
            // Hugging Face (clés tierces retirées). Seuls la passerelle Vista (Cimpress)
            // et MLKit hors ligne restent.

            // 2) Fallback ULTIME : MLKit offline (aucun réseau requis)
            String r = tryMlKit(jpegBytes, trace);
            if (r != null) {
                // On expose la trace pour diagnostiquer pourquoi les IA distantes ont échoué
                Log.w(TAG, "Fallback MLKit — trace: " + trace);
                cb.onResult(mapToFrench(r) + " (offline) — " + trace, null);
                return;
            }

            cb.onResult(null, AppLanguage.L("IA indisponible — %@", trace.toString()));
        }, "vision-gateway").start();
    }

    // ---------- Cimpress Gateway (OpenAI-compatible, Bearer auth) ----------

    private static String tryCimpress(byte[] jpeg, String baseUrl, String key,
                                      String model, StringBuilder trace) {
        try {
            String b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
            // NB : pas de "temperature" — deprecated pour Claude Opus 4 sur Bedrock via Cimpress.
            String body = "{\n" +
                    "  \"model\": " + jsonQuote(model) + ",\n" +
                    "  \"max_tokens\": 32,\n" +
                    "  \"messages\": [{\n" +
                    "    \"role\": \"user\",\n" +
                    "    \"content\": [\n" +
                    "      { \"type\": \"text\", \"text\": " + jsonQuote(PROMPT) + " },\n" +
                    "      { \"type\": \"image_url\", \"image_url\": { \"url\": \"data:image/jpeg;base64," + b64 + "\" } }\n" +
                    "    ]\n" +
                    "  }]\n" +
                    "}";

            Request req = new Request.Builder()
                    .url(baseUrl.replaceAll("/$", "") + "/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .post(RequestBody.create(body, JSON))
                    .build();

            try (Response resp = CLIENT.newCall(req).execute()) {
                String payload = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    Log.w(TAG, "Cimpress HTTP " + resp.code() + " -> " + trimLog(payload));
                    trace.append("CimpressHTTP").append(resp.code())
                         .append("[").append(shortBody(payload)).append("]; ");
                    return null;
                }
                String text = extractOpenAiContent(payload);
                Log.i(TAG, "Cimpress OK -> " + text);
                return text;
            }
        } catch (IOException e) {
            Log.e(TAG, "Cimpress IO", e);
            trace.append("CimpressIO(").append(e.getMessage()).append("); ");
            return null;
        }
    }

    // ---------- MLKit offline (dernier recours) ----------

    private static String tryMlKit(byte[] jpeg, StringBuilder trace) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bmp == null) { trace.append("MLKitDecodeKO; "); return null; }
            InputImage img = InputImage.fromBitmap(bmp, 0);
            ImageLabelerOptions options = new ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.5f)
                    .build();
            ImageLabeler labeler = ImageLabeling.getClient(options);

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> ref = new AtomicReference<>(null);
            labeler.process(img)
                    .addOnSuccessListener(labels -> {
                        if (labels != null && !labels.isEmpty()) {
                            ref.set(labels.get(0).getText());
                        }
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "MLKit fail", e);
                        trace.append("MLKit(").append(e.getMessage()).append("); ");
                        latch.countDown();
                    });
            latch.await(20, TimeUnit.SECONDS);
            String label = ref.get();
            Log.i(TAG, "MLKit -> " + label);
            return label;
        } catch (Exception e) {
            Log.e(TAG, "MLKit ex", e);
            trace.append("MLKitEx(").append(e.getMessage()).append("); ");
            return null;
        }
    }

    // ---------- Portkey (OpenAI-compatible) ----------


    /** Extrait choices[0].message.content d'une réponse OpenAI/Portkey. */
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
                    case 'n': sb.append(' '); break;
                    case 't': sb.append(' '); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(n);
                }
            } else if (ch == '"') {
                return sb.toString();
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ---------- Google Gemini direct ----------




    // ---------- Hugging Face fallback ----------



    /** Traduit un label EN de vit-base en libellé court français IT. */
    private static String mapToFrench(String raw) {
        if (raw == null) return "Objet";
        String l = raw.toLowerCase();
        if (l.contains("keyboard") || l.contains("keypad")) return "Clavier";
        if (l.contains("mouse"))                              return "Souris";
        if (l.contains("laptop") || l.contains("notebook"))   return "Ordinateur portable";
        if (l.contains("monitor") || l.contains("screen")
                || l.contains("crt") || l.contains("display"))return "Écran";
        if (l.contains("desktop") || l.contains("pc"))        return "PC de bureau";
        if (l.contains("printer"))                            return "Imprimante";
        if (l.contains("scanner"))                            return "Scanner";
        if (l.contains("cell") || l.contains("phone")
                || l.contains("mobile"))                      return "Téléphone";
        if (l.contains("tablet") || l.contains("ipad"))       return "Tablette";
        if (l.contains("headphone") || l.contains("earphone")
                || l.contains("headset"))                     return "Casque";
        if (l.contains("router") || l.contains("modem"))      return "Routeur";
        if (l.contains("cable") || l.contains("cord"))        return "Câble";
        if (l.contains("hard disc") || l.contains("hard disk")
                || l.contains("ssd") || l.contains("drive"))  return "Disque";
        if (l.contains("usb") || l.contains("flash"))         return "Clé USB";
        String[] parts = raw.split("[,\\s]+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < 2; i++) {
            if (parts[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) sb.append(parts[i].substring(1).toLowerCase());
        }
        return sb.length() == 0 ? "Objet" : sb.toString();
    }

    // ---------- helpers ----------

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("[\"'`.]", "");
        String[] parts = t.split("\\s+");
        if (parts.length > 2) t = parts[0] + " " + parts[1];
        return t;
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

    private static String trimLog(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    /** Extrait un court fragment du body d'erreur pour l'afficher à l'utilisateur. */
    private static String shortBody(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        // Chercher un champ "message" JSON s'il existe
        int m = flat.indexOf("\"message\"");
        if (m >= 0) {
            int q1 = flat.indexOf('"', flat.indexOf(':', m) + 1);
            int q2 = q1 > 0 ? flat.indexOf('"', q1 + 1) : -1;
            if (q1 > 0 && q2 > q1) flat = flat.substring(q1 + 1, q2);
        }
        return flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
    }
}
