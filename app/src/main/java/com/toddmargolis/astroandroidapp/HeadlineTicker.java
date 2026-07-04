// Copyright (c) 2025 Todd Margolis. All rights reserved.
// Delayed Vision - Cosmically Inspired Video Mirrors. See LICENSE.md for terms.
package com.toddmargolis.astroandroidapp;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fetches leaf-level event bullets from Wikipedia's "Portal:Current events" page for
 * whatever date sits `delaySeconds` in the past — used by Proxima Centauri mode's
 * scrolling headline ticker to show what was happening on the day the light now
 * arriving actually left. Source citations are stripped; every other bullet is kept
 * as-is, since grouping/topic lines (no citation link) are filtered out as non-leaves.
 */
class HeadlineTicker {

    private static final String TAG = "HeadlineTicker";
    private static final String PREFS_NAME = "headline_ticker_cache";
    private static final String KEY_ITEMS = "cached_items";
    private static final String KEY_DATE_LABEL = "cached_date_label";

    private static final String[] MONTH_NAMES = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };

    // A leaf bullet always ends in an external citation link like "[https://... (Reuters)]";
    // pure topic-grouping lines (e.g. "*[[Russo-Ukrainian War]]") never have one.
    private static final Pattern EXT_LINK =
            Pattern.compile("\\[https?://\\S+\\s+\\([^\\]]*\\)\\]");
    private static final Pattern PIPED_WIKILINK =
            Pattern.compile("\\[\\[[^\\]|]*\\|([^\\]]+)\\]\\]");
    private static final Pattern PLAIN_WIKILINK =
            Pattern.compile("\\[\\[([^\\]]+)\\]\\]");

    /** A single ticker entry: an optional parent-topic prefix, plus the sentence. */
    static class HeadlineItem {
        final String prefix;   // "" when there's no topic to show (rare)
        final String sentence;

        HeadlineItem(String prefix, String sentence) {
            this.prefix = prefix;
            this.sentence = sentence;
        }
    }

    /** Calendar date `delaySeconds` before now — the day whose events the ticker shows. */
    static Calendar lookbackDate(float delaySeconds) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.SECOND, -(int) delaySeconds);
        return cal;
    }

    private static String pageTitle(Calendar date) {
        return date.get(Calendar.YEAR) + "_" + MONTH_NAMES[date.get(Calendar.MONTH)]
                + "_" + date.get(Calendar.DAY_OF_MONTH);
    }

    /** Fixed date label shown to the left of the ticker, e.g. "February 18, 2022: ". */
    static String formatDateLabel(Calendar date) {
        return MONTH_NAMES[date.get(Calendar.MONTH)] + " " + date.get(Calendar.DAY_OF_MONTH)
                + ", " + date.get(Calendar.YEAR) + ": ";
    }

    /**
     * Blocking network fetch — must be called off the main thread.
     * Returns the day's leaf headlines, or null on failure.
     */
    static List<HeadlineItem> fetchHeadlines(Calendar date) {
        String title = pageTitle(date);
        HttpURLConnection conn = null;
        try {
            String url = "https://en.wikipedia.org/w/api.php?action=parse&page="
                    + URLEncoder.encode("Portal:Current_events/" + title, "UTF-8")
                    + "&prop=wikitext&format=json";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            JSONObject root = new JSONObject(sb.toString());
            String wikitext = root.getJSONObject("parse").getJSONObject("wikitext").getString("*");
            List<HeadlineItem> items = parseLeafHeadlines(wikitext);
            return items.isEmpty() ? null : items;
        } catch (Exception e) {
            Log.e(TAG, "Headline fetch failed for " + title, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Extracts leaf-level bullet sentences (those carrying an external citation link) and
     * prefixes each with its nearest parent topic — e.g. "Battle of Sievierodonetsk: Russian
     * forces shell..." — so consecutive leaves from different stories read as distinct items
     * in the ticker instead of running together. Falls back to the category name (the bold
     * header, e.g. "Law and crime") when a leaf hangs directly off it with no intermediate
     * topic line.
     */
    static List<HeadlineItem> parseLeafHeadlines(String wikitext) {
        List<HeadlineItem> result = new ArrayList<>();

        int start = wikitext.indexOf("below this line -->");
        int end = wikitext.indexOf("<!-- All news items above this line -->");
        String body = (start >= 0 && end > start)
                ? wikitext.substring(start + "below this line -->".length(), end)
                : wikitext;

        String currentCategory = "";
        // ancestors.get(i) holds the nearest topic-grouping text seen at list depth (i+1).
        List<String> ancestors = new ArrayList<>();

        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (!line.startsWith("*")) {
                if (line.startsWith("'''") && line.endsWith("'''")) {
                    currentCategory = cleanText(line.substring(3, line.length() - 3));
                    ancestors.clear();
                }
                continue;
            }

            int depth = 0;
            while (depth < line.length() && line.charAt(depth) == '*') depth++;
            String rest = line.substring(depth).trim();

            if (EXT_LINK.matcher(rest).find()) {
                // Leaf: nearest parent is the last grouping line seen one depth shallower.
                String prefix = (depth >= 2 && ancestors.size() >= depth - 1)
                        ? ancestors.get(depth - 2)
                        : currentCategory;
                String sentence = cleanText(EXT_LINK.matcher(rest).replaceAll("").trim());
                if (!sentence.isEmpty()) {
                    result.add(new HeadlineItem(prefix, sentence));
                }
            } else {
                // Topic-grouping line: record it at this depth, discarding stale deeper entries.
                String text = cleanText(rest);
                while (ancestors.size() >= depth) ancestors.remove(ancestors.size() - 1);
                ancestors.add(text);
            }
        }
        return result;
    }

    private static String cleanText(String s) {
        s = PIPED_WIKILINK.matcher(s).replaceAll("$1");
        s = PLAIN_WIKILINK.matcher(s).replaceAll("$1");
        s = s.replace("'''", "").replace("''", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Last successfully fetched headline items, regardless of which day they came from. */
    static List<HeadlineItem> readCachedItems(Context ctx) {
        String json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ITEMS, null);
        List<HeadlineItem> items = new ArrayList<>();
        if (json == null) return items;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                items.add(new HeadlineItem(obj.getString("prefix"), obj.getString("sentence")));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse cached headlines", e);
        }
        return items;
    }

    /** Date label matching the last successfully fetched headline items. */
    static String readCachedDateLabel(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DATE_LABEL, null);
    }

    static void writeCache(Context ctx, List<HeadlineItem> items, String dateLabel) {
        JSONArray arr = new JSONArray();
        for (HeadlineItem item : items) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("prefix", item.prefix);
                obj.put("sentence", item.sentence);
            } catch (JSONException e) {
                // Unreachable: keys are non-null string literals.
            }
            arr.put(obj);
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, arr.toString())
                .putString(KEY_DATE_LABEL, dateLabel)
                .apply();
    }
}
