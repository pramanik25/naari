package com.example.naarishakti.journey;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts an Indian registration number (e.g. "KA 01 AB 1234") from OCR text. OCR confuses
 * O/0, I/1, S/5, B/8, Z/2 and G/6, so each candidate window is repaired by position (letters
 * where letters belong, digits where digits belong) before it is checked against
 * {@code [A-Z]{2}\s?[0-9]{1,2}\s?[A-Z]{0,3}\s?[0-9]{4}} and a known state code.
 */
final class PlateParser {

    static final Pattern PLATE = Pattern.compile("[A-Z]{2}\\s?[0-9]{1,2}\\s?[A-Z]{0,3}\\s?[0-9]{4}");

    private static final Set<String> STATES = new HashSet<>(Arrays.asList(
            "AN", "AP", "AR", "AS", "BR", "CG", "CH", "DD", "DL", "DN", "GA", "GJ", "HP", "HR",
            "JH", "JK", "KA", "KL", "LA", "LD", "MH", "ML", "MN", "MP", "MZ", "NL", "OD", "OR",
            "PB", "PY", "RJ", "SK", "TN", "TR", "TS", "UK", "UA", "UP", "WB"));

    private static final int MAX_FIXES = 2;

    private PlateParser() {}

    private static final class Candidate {
        final String formatted;
        final int fixes;
        final int length;

        Candidate(String formatted, int fixes, int length) {
            this.formatted = formatted;
            this.fixes = fixes;
            this.length = length;
        }

        boolean betterThan(@Nullable Candidate o) {
            if (o == null) return true;
            if (fixes != o.fixes) return fixes < o.fixes;
            return length > o.length;
        }
    }

    /** The best plate in {@code text}, formatted "SS NN XXX NNNN", or null. */
    @Nullable
    static String find(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String upper = text.toUpperCase(Locale.US);
        String[] lines = upper.split("\\r?\\n");
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            sources.add(lines[i]);
            // Two-row plates (common on autos and bikes): join each line with the next.
            if (i + 1 < lines.length) sources.add(lines[i] + lines[i + 1]);
        }
        Candidate best = null;
        for (String s : sources) {
            String compact = s.replaceAll("[^A-Z0-9]", "");
            Candidate c = scan(compact);
            if (c != null && c.betterThan(best)) best = c;
        }
        return best == null ? null : best.formatted;
    }

    /** Normalises a typed plate: uppercase, single spaces, O/0 and I/1 repaired where unambiguous. */
    static String normalise(String typed) {
        if (typed == null) return "";
        String compact = typed.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        Candidate c = fix(compact);
        return c != null ? c.formatted : typed.trim().toUpperCase(Locale.US).replaceAll("\\s+", " ");
    }

    @Nullable
    private static Candidate scan(String s) {
        Candidate best = null;
        int n = s.length();
        for (int start = 0; start + 7 <= n; start++) {
            for (int len = Math.min(10, n - start); len >= 7; len--) {
                Candidate c = fix(s.substring(start, start + len));
                if (c != null && c.betterThan(best)) best = c;
            }
        }
        return best;
    }

    /** Tries every split of a compact window into state/district/series/number. */
    @Nullable
    private static Candidate fix(String w) {
        int n = w.length();
        if (n < 7 || n > 10) return null;
        Candidate best = null;
        int[] fixes = new int[1];
        for (int district = 1; district <= 2; district++) {
            int series = n - 2 - district - 4;
            if (series < 0 || series > 3) continue;
            fixes[0] = 0;
            String st = toLetters(w.substring(0, 2), fixes);
            String di = toDigits(w.substring(2, 2 + district), fixes);
            String se = toLetters(w.substring(2 + district, 2 + district + series), fixes);
            String nu = toDigits(w.substring(n - 4), fixes);
            if (st == null || di == null || se == null || nu == null) continue;
            if (fixes[0] > MAX_FIXES || !STATES.contains(st)) continue;
            if ("0000".equals(nu)) continue;
            String formatted = se.isEmpty()
                    ? st + " " + di + " " + nu
                    : st + " " + di + " " + se + " " + nu;
            if (!PLATE.matcher(formatted).matches()) continue;
            Candidate c = new Candidate(formatted, fixes[0], n);
            if (c.betterThan(best)) best = c;
        }
        return best;
    }

    @Nullable
    private static String toLetters(String s, int[] fixes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                sb.append(ch);
                continue;
            }
            char mapped;
            switch (ch) {
                case '0': mapped = 'O'; break;
                case '1': mapped = 'I'; break;
                case '2': mapped = 'Z'; break;
                case '4': mapped = 'A'; break;
                case '5': mapped = 'S'; break;
                case '6': mapped = 'G'; break;
                case '8': mapped = 'B'; break;
                default: return null;
            }
            fixes[0]++;
            sb.append(mapped);
        }
        return sb.toString();
    }

    @Nullable
    private static String toDigits(String s, int[] fixes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
                continue;
            }
            char mapped;
            switch (ch) {
                case 'O': case 'Q': case 'D': mapped = '0'; break;
                case 'I': case 'L': case 'T': mapped = '1'; break;
                case 'Z': mapped = '2'; break;
                case 'A': mapped = '4'; break;
                case 'S': mapped = '5'; break;
                case 'G': mapped = '6'; break;
                case 'B': mapped = '8'; break;
                default: return null;
            }
            fixes[0]++;
            sb.append(mapped);
        }
        return sb.toString();
    }
}
