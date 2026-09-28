package com.naqqa.elasticsearch.search.suggest.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class CompletionScaleHarness {

    private CompletionScaleHarness() {
    }

    private static final String[] WORDS = {
        "wireless", "bluetooth", "stainless", "steel", "organic", "cotton", "leather", "portable",
        "rechargeable", "premium", "classic", "vintage", "modern", "compact", "digital", "smart",
        "outdoor", "indoor", "waterproof", "adjustable", "professional", "heavy", "duty", "eco",
        "friendly", "kids", "adult", "large", "small", "medium", "black", "white", "blue", "red",
        "kitchen", "office", "garden", "camping", "travel", "fitness", "gaming", "wireless", "mouse",
        "keyboard", "charger", "cable", "case", "cover", "stand", "mount", "speaker", "headphone"
    };

    public static void main(String[] args) throws Exception {
        int numTitles = Integer.parseInt(args[0]);
        Random random = new Random(42);

        int numBases = 2800;
        int perBase = (numTitles + numBases - 1) / numBases;
        String[] bases = new String[numBases];
        for (int b = 0; b < numBases; b++) {
            StringBuilder sb = new StringBuilder(40);
            for (int w = 0; w < 4; w++) {
                if (w > 0) {
                    sb.append(' ');
                }
                sb.append(WORDS[random.nextInt(WORDS.length)]);
            }
            bases[b] = sb.toString();
        }

        List<CompletionEntry.Input> inputs = new ArrayList<>(numTitles);
        int made = 0;
        outer:
        for (int b = 0; b < numBases; b++) {
            for (int v = 0; v < perBase; v++) {
                if (made >= numTitles) {
                    break outer;
                }
                String title = bases[b] + " " + String.format("%05d", v);
                inputs.add(new CompletionEntry.Input(title, 1 + (made % 1000)));
                made++;
            }
        }

        CompletionSuggester suggester = new CompletionSuggester(inputs);

        List<CompletionEntry> prefixHits = suggester.suggest("wireless", 10, null);
        List<CompletionEntry> fuzzyHits = suggester.suggestFuzzy("wireles", 10, new FuzzyOptions().maxEdits(1), null);

        System.out.println("DONE " + numTitles + " prefixHits=" + prefixHits.size() + " fuzzyHits=" + fuzzyHits.size());
        System.out.flush();
    }
}
