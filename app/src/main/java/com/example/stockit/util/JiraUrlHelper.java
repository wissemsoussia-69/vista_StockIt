package com.example.stockit.util;

import com.example.stockit.BuildConfig;

import java.net.URI;
import java.text.Normalizer;
import java.util.Locale;

public final class JiraUrlHelper {

    private static final String DEFAULT_BASE = "https://vistaprint.atlassian.net";
    private static final String ASSETS_SCHEMA_ROOT = "/jira/assets/object-schema/247";
    private static final String ASSETS_ALL = "?view=list&typeId=905";
    private static final String ASSETS_MONITORS = "?typeId=938&view=list";
    private static final String ASSETS_COMPUTERS = "?typeId=939&view=list";
    private static final String ASSETS_PERIPHERALS = "?typeId=940&view=list";

    private JiraUrlHelper() {}

    public static String siteBaseUrl() {
        String raw = BuildConfig.JIRA_BASE_URL;
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_BASE;

        String candidate = raw.trim();
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            candidate = "https://" + candidate;
        }

        try {
            URI uri = new URI(candidate);
            String scheme = (uri.getScheme() == null || uri.getScheme().isEmpty()) ? "https" : uri.getScheme();
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return DEFAULT_BASE;

            String normalizedHost = host.toLowerCase();
            if (normalizedHost.endsWith(".atlassian.net")) {
                return scheme + "://" + normalizedHost;
            }

            int port = uri.getPort();
            return port > 0
                    ? scheme + "://" + normalizedHost + ":" + port
                    : scheme + "://" + normalizedHost;
        } catch (Exception ignored) {
            return DEFAULT_BASE;
        }
    }

    public static String apiUrl(String path) {
        String p = (path == null || path.isEmpty()) ? "" : path;
        if (!p.startsWith("/")) p = "/" + p;
        return siteBaseUrl() + p;
    }

    public static String browseUrl(String jiraKey) {
        String key = jiraKey == null ? "" : jiraKey.trim();
        return siteBaseUrl() + "/browse/" + key;
    }

    public static String assetsAllListUrl() {
        return siteBaseUrl() + ASSETS_SCHEMA_ROOT + ASSETS_ALL;
    }

    public static String assetsMonitorsListUrl() {
        return siteBaseUrl() + ASSETS_SCHEMA_ROOT + ASSETS_MONITORS;
    }

    public static String assetsComputersListUrl() {
        return siteBaseUrl() + ASSETS_SCHEMA_ROOT + ASSETS_COMPUTERS;
    }

    public static String assetsPeripheralsListUrl() {
        return siteBaseUrl() + ASSETS_SCHEMA_ROOT + ASSETS_PERIPHERALS;
    }

    public static String assetsListUrlForName(String assetName) {
        if (assetName == null) return assetsAllListUrl();
        String n = normalize(assetName);

        if (n.contains("monitor") || n.contains("screen") || n.contains("display")) {
            return assetsMonitorsListUrl();
        }
        if (n.contains("computer") || n.contains("laptop") || n.contains("desktop") || n.contains("pc") || n.contains("notebook")) {
            return assetsComputersListUrl();
        }
        if (n.contains("keyboard") || n.contains("mouse") || n.contains("headset") || n.contains("dock")
            || n.contains("adapter") || n.contains("peripheral")
                || n.contains("audio") || n.contains("webcam") || n.contains("microphone")) {
            return assetsPeripheralsListUrl();
        }
        return assetsAllListUrl();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
