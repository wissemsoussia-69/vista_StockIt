package com.example.stockit.util;

public final class LegacyTextNormalizer {

    private LegacyTextNormalizer() {}

    public static String toEnglishProductName(String raw) {
        if (raw == null) return "";
        String out = raw;
        out = out.replaceAll("(?iu)\\b(?:ecran|ecrans|écran|écrans)\\b", "Monitor");
        out = out.replaceAll("(?iu)\\b(?:souris)\\b", "Mouse");
        out = out.replaceAll("(?iu)\\b(?:clavier|claviers)\\b", "Keyboard");
        out = out.replaceAll("(?iu)\\b(?:casque|casques)\\b", "Headset");
        out = out.replaceAll("(?iu)\\b(?:headphone|headphones)\\b", "Headset");
        out = out.replaceAll("(?iu)\\b(?:laptop|laptops)\\b", "computer");
        out = out.replaceAll("(?iu)\\b(?:pouce|pouces)\\b", "inch");
        return out;
    }
}
