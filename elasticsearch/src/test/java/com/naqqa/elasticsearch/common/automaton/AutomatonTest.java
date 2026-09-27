package com.naqqa.elasticsearch.common.automaton;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public class AutomatonTest {

    private static final String ALPHABET = "abc";

    private static String randomString(SplittableRandom r, int maxLen) {
        int len = r.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    @Test
    public void regexMatchesJavaUtilRegexOnRandomStrings() {
        String[] patterns = {"a.c", "a*b", "(a|b)c*", "a?b+c", "[ab]{2,3}c", "a{1,2}(b|c)*"};
        SplittableRandom r = new SplittableRandom(123);
        for (String p : patterns) {
            CharacterRunAutomaton cra = new CharacterRunAutomaton(new RegExp(p).toAutomaton());
            java.util.regex.Pattern jp = java.util.regex.Pattern.compile(p);
            for (int i = 0; i < 300; i++) {
                String s = randomString(r, 6);
                boolean expected = jp.matcher(s).matches();
                boolean actual = cra.run(s);
                assertEquals(expected, actual, "pattern=" + p + " s=" + s);
            }
        }
    }

    @Test
    public void unionConcatIntersectionComplement() {
        Automaton a = Automata.makeString("cat");
        Automaton b = Automata.makeString("dog");
        Automaton union = Operations.union(a, b);
        assertTrue(Operations.run(union, "cat"));
        assertTrue(Operations.run(union, "dog"));
        assertFalse(Operations.run(union, "bird"));

        Automaton cat = Automata.makeString("cat");
        Automaton s = Automata.makeString("s");
        Automaton concat = Operations.concatenate(cat, s);
        assertTrue(Operations.run(concat, "cats"));
        assertFalse(Operations.run(concat, "cat"));

        Automaton anyThreeLetter = Operations.repeat(Automata.makeCharRange('a', 'z'), 3, 3);
        Automaton inter = Operations.intersection(union, anyThreeLetter);
        assertTrue(Operations.run(inter, "cat"));
        assertTrue(Operations.run(inter, "dog"));
        assertFalse(Operations.run(inter, "bird"));

        Automaton notCat = Operations.complement(cat, Automaton.MAX_CODE_POINT, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
        assertFalse(Operations.run(notCat, "cat"));
        assertTrue(Operations.run(notCat, "dog"));
        assertTrue(Operations.run(notCat, ""));

        Automaton minus = Operations.minus(union, cat, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
        assertFalse(Operations.run(minus, "cat"));
        assertTrue(Operations.run(minus, "dog"));
    }

    @Test
    public void minimizationPreservesLanguage() {
        SplittableRandom r = new SplittableRandom(7);
        for (int iter = 0; iter < 20; iter++) {
            List<String> strings = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                strings.add(randomString(r, 5));
            }
            Automaton nfa = Automata.makeStringUnion(strings);
            Automaton dfa = Operations.determinize(nfa, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
            Automaton min = Operations.minimize(dfa, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
            assertTrue(Operations.sameLanguage(dfa, min), "iter " + iter);
            for (String s : strings) {
                assertTrue(Operations.run(min, s));
            }
            for (int i = 0; i < 20; i++) {
                String s = randomString(r, 6);
                assertEquals(Operations.run(dfa, s), Operations.run(min, s), "s=" + s);
            }
        }
    }

    @Test
    public void wildcardAndPrefixAutomata() {
        Automaton wc = WildcardAutomata.toAutomaton("a?c*");
        CharacterRunAutomaton cra = new CharacterRunAutomaton(wc);
        assertTrue(cra.run("abc"));
        assertTrue(cra.run("abcxyz"));
        assertFalse(cra.run("ac"));
        assertFalse(cra.run("xbc"));

        Automaton prefix = Automata.makePrefix("foo");
        CharacterRunAutomaton pra = new CharacterRunAutomaton(prefix);
        assertTrue(pra.run("foobar"));
        assertTrue(pra.run("foo"));
        assertFalse(pra.run("fo"));
        assertFalse(pra.run("barfoo"));
    }

    @Test
    public void toughToComplexToDeterminizeThrows() {
        Automaton hard = Operations.repeat(Automata.makeCharRange('a', 'z'), 40, 40);
        try {
            Operations.determinize(hard, 10);
        } catch (TooComplexToDeterminizeException expected) {
            return;
        }
        assertTrue(false, "expected TooComplexToDeterminizeException");
    }

    @Test
    public void compiledAutomatonIntersectsSortedTermSource() {
        byte[][] terms = {
            "apple".getBytes(StandardCharsets.UTF_8),
            "application".getBytes(StandardCharsets.UTF_8),
            "banana".getBytes(StandardCharsets.UTF_8),
            "apply".getBytes(StandardCharsets.UTF_8),
            "grape".getBytes(StandardCharsets.UTF_8)
        };
        ArrayTermSource source = ArrayTermSource.ofUnsorted(terms);
        Automaton prefixAutomaton = Automata.makeUtf8Prefix("app");
        CompiledAutomaton compiled = new CompiledAutomaton(prefixAutomaton, true, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
        assertEquals(CompiledAutomaton.Type.NORMAL, compiled.getType());
        List<String> matched = new ArrayList<>();
        CompiledAutomaton.TermIterator it = compiled.intersect(source);
        for (byte[] t = it.next(); t != null; t = it.next()) {
            matched.add(new String(t, StandardCharsets.UTF_8));
        }
        matched.sort(String::compareTo);
        assertEquals(List.of("apple", "application", "apply"), matched);
    }

    @Test
    public void compiledAutomatonSingleAndAllTypes() {
        CompiledAutomaton single = new CompiledAutomaton(Automata.makeBinary("hello".getBytes(StandardCharsets.UTF_8)), true, 1000);
        assertEquals(CompiledAutomaton.Type.SINGLE, single.getType());
        assertTrue(single.run("hello".getBytes(StandardCharsets.UTF_8)));
        assertFalse(single.run("world".getBytes(StandardCharsets.UTF_8)));

        CompiledAutomaton none = new CompiledAutomaton(Automata.makeEmpty(), true, 1000);
        assertEquals(CompiledAutomaton.Type.NONE, none.getType());
        assertFalse(none.run(new byte[0]));
    }

    @Test
    public void levenshteinAutomatonMatchesBruteForce() {
        SplittableRandom r = new SplittableRandom(99);
        for (int maxEdits = 0; maxEdits <= 2; maxEdits++) {
            for (boolean transpositions : new boolean[] {false, true}) {
                for (int iter = 0; iter < 15; iter++) {
                    String word = randomString(r, 5);
                    Automaton automaton = new LevenshteinAutomata(word, maxEdits, transpositions).toAutomaton(0);
                    CharacterRunAutomaton cra = new CharacterRunAutomaton(automaton);
                    for (int i = 0; i < 40; i++) {
                        String candidate = randomString(r, 6);
                        int dist = transpositions
                            ? StringDistance.damerauLevenshtein(word, candidate)
                            : StringDistance.levenshtein(word, candidate);
                        boolean expected = dist <= maxEdits;
                        boolean actual = cra.run(candidate);
                        assertEquals(expected, actual, "word=" + word + " cand=" + candidate + " maxEdits=" + maxEdits
                            + " trans=" + transpositions + " dist=" + dist);
                    }
                }
            }
        }
    }

    @Test
    public void levenshteinAutomatonWithPrefixLength() {
        Automaton automaton = new LevenshteinAutomata("hello", 1, false).toAutomaton(3);
        CharacterRunAutomaton cra = new CharacterRunAutomaton(automaton);
        assertTrue(cra.run("hello"));
        assertTrue(cra.run("hellp"));
        assertFalse(cra.run("help"));
        assertFalse(cra.run("xello"));
    }

    @Test
    public void stringDistanceFunctions() {
        assertEquals(3, StringDistance.levenshtein("kitten", "sitting"));
        assertEquals(1, StringDistance.damerauLevenshtein("ab", "ba"));
        assertEquals(2, StringDistance.levenshtein("ab", "ba"));
        assertTrue(StringDistance.jaroWinkler("MARTHA", "MARHTA") > 0.9);
        assertTrue(StringDistance.jaro("MARTHA", "MARHTA") > 0.9);
        assertEquals(1.0, StringDistance.jaro("", ""), 1e-9);
        assertTrue(StringDistance.ngramSimilarity("night", "nacht", 2) > 0.0);
        assertEquals(1.0, StringDistance.ngramSimilarity("abc", "abc", 2), 1e-9);
        assertEquals(0.0, StringDistance.ngramSimilarity("abc", "xyz", 2), 1e-9);
    }

    @Test
    public void caseInsensitiveFlag() {
        Automaton automaton = new RegExp("HELLO", RegExp.ALL, RegExp.CASE_INSENSITIVE).toAutomaton();
        CharacterRunAutomaton cra = new CharacterRunAutomaton(automaton);
        assertTrue(cra.run("hello"));
        assertTrue(cra.run("HELLO"));
        assertTrue(cra.run("HeLLo"));
        assertFalse(cra.run("hell"));
    }
}
