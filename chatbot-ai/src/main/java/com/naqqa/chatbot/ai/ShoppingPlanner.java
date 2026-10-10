package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.retrieval.RankedItem;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.i18n.ChatResources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShoppingPlanner {

    public static final String KG = "kg";
    public static final String L = "l";
    public static final String PCS = "pcs";

    private static final Pattern SIZE = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(kg|кг|gr|g|г|ml|мл|l|л|buc|bucati|pcs|шт)(?![\\p{L}])");
    private static final Pattern MULTI = Pattern.compile("(\\d{1,2})\\s*[x×х]\\s*(\\d+(?:[.,]\\d+)?)\\s*(kg|кг|gr|g|г|ml|мл|l|л)(?![\\p{L}])");

    public record PackSize(double amount, String unit) {
    }

    public record Offer(RankedItem item, int packs, double subtotal, double unitPrice) {
    }

    public record Picked(ChatLanguages.BasketItem line, double need, Offer offer) {
    }

    public record Plan(List<Picked> picked, List<String> skipped, List<String> missing, double total) {
    }

    public record Food(String ro, String ru, String en, double kcal, double protein, double fat, double carbs, String meals) {

        public String name(String lang) {
            String v = "ru".equals(lang) ? ru : "en".equals(lang) ? en : ro;
            return v == null || v.isBlank() ? ro : v;
        }
    }

    public record Portion(String meal, Food food, int grams) {

        public double kcal() {
            return food.kcal() * grams / 100.0;
        }
    }

    public record Menu(int target, List<Portion> portions) {

        public double kcal() {
            return portions.stream().mapToDouble(Portion::kcal).sum();
        }

        public double protein() {
            return portions.stream().mapToDouble(p -> p.food().protein() * p.grams() / 100.0).sum();
        }

        public double fat() {
            return portions.stream().mapToDouble(p -> p.food().fat() * p.grams() / 100.0).sum();
        }

        public double carbs() {
            return portions.stream().mapToDouble(p -> p.food().carbs() * p.grams() / 100.0).sum();
        }
    }

    public static final Map<String, Double> MEAL_SHARES;

    static {
        Map<String, Double> shares = new LinkedHashMap<>();
        shares.put("breakfast", 0.25);
        shares.put("lunch", 0.35);
        shares.put("dinner", 0.30);
        shares.put("snack", 0.10);
        MEAL_SHARES = java.util.Collections.unmodifiableMap(shares);
    }

    private ShoppingPlanner() {
    }

    public static PackSize packSize(String title) {
        if (title == null) {
            return null;
        }
        String t = TextNormalizer.fold(title);
        Matcher multi = MULTI.matcher(t);
        if (multi.find()) {
            PackSize one = normalize(number(multi.group(2)), multi.group(3));
            return one == null ? null : new PackSize(one.amount() * Integer.parseInt(multi.group(1)), one.unit());
        }
        Matcher m = SIZE.matcher(t);
        PackSize found = null;
        while (m.find()) {
            PackSize p = normalize(number(m.group(1)), m.group(2));
            if (p != null) {
                found = p;
            }
        }
        return found;
    }

    private static double number(String v) {
        return Double.parseDouble(v.replace(',', '.'));
    }

    private static PackSize normalize(double amount, String unit) {
        if (amount <= 0) {
            return null;
        }
        return switch (unit) {
            case "kg", "кг" -> new PackSize(amount, KG);
            case "g", "gr", "г" -> new PackSize(amount / 1000.0, KG);
            case "l", "л" -> new PackSize(amount, L);
            case "ml", "мл" -> new PackSize(amount / 1000.0, L);
            default -> new PackSize(amount, PCS);
        };
    }

    public static int packsNeeded(double need, String unit, PackSize size) {
        if (need <= 0) {
            return 0;
        }
        if (size != null && size.unit().equals(unit) && size.amount() > 0) {
            // Rounding up always over-buys small packs (6 L -> 7 x 0.9 L); accept up to 10% short of the target instead.
            int up = Math.max(1, (int) Math.ceil(need / size.amount() - 1e-9));
            int down = up - 1;
            if (down >= 1 && down * size.amount() >= need * 0.9 - 1e-9) {
                return down;
            }
            return up;
        }
        if (PCS.equals(unit)) {
            return Math.max(1, (int) Math.ceil(need - 1e-9));
        }
        return Math.max(1, (int) Math.ceil(need - 1e-9));
    }

    private static final List<String> COSMETIC = List.of("toaleta", "parfum", "cosmetic", "dus ", "sampon", "crema", "deodorant",
            "lotiune", "balsam", "demachiant", "masca", "ruj", "sapun");
    private static final int MAX_PACKS = 24;
    private static final int MAX_PACKS_UNKNOWN_SIZE = 3;
    private static final double MAX_LINE_TOTAL = 1000.0;

    /** Words a product title must not contain for a shopping-list term (folded, substring match). */
    static List<String> avoid(String term) {
        String t = TextNormalizer.fold(term == null ? "" : term).trim();
        List<String> out = new ArrayList<>();
        if (t.equals("apa") || t.startsWith("apa ") || t.equals("вода") || t.equals("water")) {
            out.addAll(COSMETIC);
            out.addAll(List.of("de gura", "oxigenata", "distilata", "baterie", "de rufe", "bors", "vitamin"));
        } else if (t.equals("paste") || t.equals("pasta") || t.startsWith("paste ") || t.equals("макароны") || t.equals("pasta alimentara")) {
            out.addAll(List.of("tomat", "rosii", "bulion", "ketchup", "dinti", "dentar", "ardei", "usturoi", "peste", "ficat", "pate", "ciocolat", "nuca", "alune",
                    "migdal", "susan", "sapun", "curat", "rufe", "lipit", "pentru par", "lemn", "perete"));
        } else if (t.equals("ulei") || t.startsWith("ulei ")) {
            out.addAll(COSMETIC);
            out.addAll(List.of("esential", "motor", "masaj", "corp", "pentru par", "de par", "ricin", "hidratant", "bronzat",
                    "auto", "cannabis", "cbd", "aromat", "lampa", "mobil", "hidraulic"));
        }
        return out;
    }

    public static Offer cheapest(List<RankedItem> items, double need, String unit, int skip) {
        return cheapest(items, need, unit, skip, null);
    }

    public static Offer cheapest(List<RankedItem> items, double need, String unit, int skip, String term) {
        List<String> avoid = avoid(term);
        List<Offer> offers = new ArrayList<>();
        for (RankedItem item : items) {
            Double price = item.candidate().price();
            if (price == null || price <= 0) {
                continue;
            }
            if (!avoid.isEmpty()) {
                String title = TextNormalizer.fold(item.candidate().title("ro") + " " + item.candidate().title("ru"));
                if (avoid.stream().anyMatch(title::contains)) {
                    continue;
                }
            }
            PackSize size = packSize(item.candidate().title("ro"));
            if (size == null) {
                size = packSize(item.candidate().title("ru"));
            }
            boolean sized = size != null && size.unit().equals(unit) && size.amount() > 0;
            int packs = packsNeeded(need, unit, size);
            if (packs > MAX_PACKS || (!sized && !PCS.equals(unit) && packs > MAX_PACKS_UNKNOWN_SIZE)) {
                continue;
            }
            double subtotal = round2(packs * price);
            if (subtotal > MAX_LINE_TOTAL) {
                continue;
            }
            double unitPrice = sized ? price / size.amount() : price;
            offers.add(new Offer(item, packs, subtotal, unitPrice));
        }
        offers.sort(java.util.Comparator.comparingDouble(Offer::subtotal));
        if (offers.isEmpty()) {
            return null;
        }
        return offers.get(Math.min(Math.max(0, skip), offers.size() - 1));
    }

    public static double factor(String period, int people) {
        double p = "day".equals(period) ? 1 / 7.0 : "month".equals(period) ? 30 / 7.0 : 1.0;
        return p * Math.max(1, people);
    }

    public static Plan greedy(List<ChatLanguages.BasketItem> lines, Map<String, List<RankedItem>> candidates, double factor,
                              Double budget, int skip) {
        List<ChatLanguages.BasketItem> ordered = new ArrayList<>();
        for (ChatLanguages.BasketItem l : lines) {
            if (l.essential()) {
                ordered.add(l);
            }
        }
        for (ChatLanguages.BasketItem l : lines) {
            if (!l.essential()) {
                ordered.add(l);
            }
        }
        List<Picked> picked = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        double total = 0;
        for (ChatLanguages.BasketItem line : ordered) {
            double need = line.qty() * factor;
            if (PCS.equals(line.unit())) {
                need = Math.max(1, Math.round(need));
            }
            Offer offer = cheapest(candidates.getOrDefault(line.term(), List.of()), need, line.unit(), skip, line.term());
            if (offer == null) {
                missing.add(line.term());
                continue;
            }
            if (budget != null && total + offer.subtotal() > budget + 1e-6) {
                skipped.add(line.term());
                continue;
            }
            picked.add(new Picked(line, need, offer));
            total += offer.subtotal();
        }
        return new Plan(picked, skipped, missing, round2(total));
    }

    public static String quantity(double need, String unit) {
        String n = need == Math.rint(need) ? String.valueOf((long) need) : String.format(Locale.ROOT, "%.1f", need);
        return switch (unit) {
            case KG -> n + " kg";
            case L -> n + " L";
            default -> n + " buc";
        };
    }

    public static List<Food> foods(ChatResources resources) {
        List<Food> out = new ArrayList<>();
        String text = resources == null ? null : resources.text(ChatResources.ROOT + "nutrition.tsv");
        if (text == null) {
            return out;
        }
        boolean header = true;
        for (String raw : text.split("\\r?\\n")) {
            if (header) {
                header = false;
                continue;
            }
            String[] c = raw.split("\t");
            if (c.length < 8) {
                continue;
            }
            try {
                out.add(new Food(c[0].trim(), c[1].trim(), c[2].trim(), Double.parseDouble(c[3]), Double.parseDouble(c[4]),
                        Double.parseDouble(c[5]), Double.parseDouble(c[6]), c[7].trim()));
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    public static Food food(List<Food> foods, String ro) {
        for (Food f : foods) {
            if (f.ro().equalsIgnoreCase(ro)) {
                return f;
            }
        }
        return null;
    }

    public static Menu menu(int target, Map<String, List<String>> meals, List<Food> foods) {
        List<Portion> portions = new ArrayList<>();
        for (Map.Entry<String, Double> share : MEAL_SHARES.entrySet()) {
            List<Food> items = new ArrayList<>();
            for (String name : meals.getOrDefault(share.getKey(), List.of())) {
                Food f = food(foods, name);
                if (f != null && f.kcal() > 0) {
                    items.add(f);
                }
            }
            if (items.isEmpty()) {
                continue;
            }
            double mealKcal = target * share.getValue();
            double[] split = items.size() == 1 ? new double[]{1} : items.size() == 2 ? new double[]{0.6, 0.4}
                    : new double[]{0.45, 0.35, 0.20};
            double carry = 0;
            int[] grams = new int[items.size()];
            for (int i = items.size() - 1; i >= 0; i--) {
                double part = mealKcal * (i < split.length ? split[i] : 0) + (i == 0 ? carry : 0);
                Food f = items.get(i);
                int cap = f.kcal() < 60 ? 300 : 400;
                int g = (int) Math.round(part / f.kcal() * 100 / 10.0) * 10;
                if (g > cap) {
                    carry += (g - cap) * f.kcal() / 100.0;
                    g = cap;
                }
                grams[i] = Math.max(10, g);
            }
            for (int i = 0; i < items.size(); i++) {
                portions.add(new Portion(share.getKey(), items.get(i), grams[i]));
            }
        }
        Menu menu = new Menu(target, portions);
        double diff = target - menu.kcal();
        if (Math.abs(diff) > target * 0.05 && !portions.isEmpty()) {
            Portion densest = portions.get(0);
            for (Portion p : portions) {
                if (p.food().kcal() > densest.food().kcal()) {
                    densest = p;
                }
            }
            int delta = (int) Math.round(diff / densest.food().kcal() * 100 / 10.0) * 10;
            int idx = portions.indexOf(densest);
            portions.set(idx, new Portion(densest.meal(), densest.food(), Math.max(10, densest.grams() + delta)));
            menu = new Menu(target, portions);
        }
        return menu;
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
