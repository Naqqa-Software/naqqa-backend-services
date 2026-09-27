package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class CJKBigramFilter extends TokenFilter {

    private final boolean han;
    private final boolean hiragana;
    private final boolean katakana;
    private final boolean hangul;
    private final boolean outputUnigrams;

    private final Deque<Token> outputQueue = new ArrayDeque<>();
    private final List<Token> runBuffer = new ArrayList<>();
    private Token pendingNonMatch;
    private boolean finished;
    private int absPos = -1;
    private int lastEmittedAbsPos = -1;

    public CJKBigramFilter(TokenStream input, boolean han, boolean hiragana, boolean katakana, boolean hangul, boolean outputUnigrams) {
        super(input);
        this.han = han;
        this.hiragana = hiragana;
        this.katakana = katakana;
        this.hangul = hangul;
        this.outputUnigrams = outputUnigrams;
    }

    @Override
    public void reset() {
        super.reset();
        outputQueue.clear();
        runBuffer.clear();
        pendingNonMatch = null;
        finished = false;
        absPos = -1;
        lastEmittedAbsPos = -1;
    }

    @Override
    public boolean incrementToken() {
        if (!outputQueue.isEmpty()) {
            token.clear();
            token.copyFrom(outputQueue.poll());
            return true;
        }
        if (finished) {
            return false;
        }
        Token first;
        if (pendingNonMatch != null) {
            first = pendingNonMatch;
            pendingNonMatch = null;
        } else {
            if (!input.incrementToken()) {
                finished = true;
                return false;
            }
            first = token.copy();
            absPos += first.positionIncrement();
        }
        String cls = classify(first);
        if (cls == null) {
            enqueue(first.term(), absPos, first.positionLength(), first.startOffset(), first.endOffset(), first.type());
            token.clear();
            token.copyFrom(outputQueue.poll());
            return true;
        }
        runBuffer.clear();
        runBuffer.add(first);
        int runStartAbsPos = absPos;
        while (input.incrementToken()) {
            Token next = token.copy();
            Token last = runBuffer.get(runBuffer.size() - 1);
            boolean adjacent = next.startOffset() == last.endOffset() && next.positionIncrement() == 1;
            String nextCls = classify(next);
            if (adjacent && cls.equals(nextCls)) {
                absPos += next.positionIncrement();
                runBuffer.add(next);
            } else {
                absPos += next.positionIncrement();
                pendingNonMatch = next;
                break;
            }
        }
        buildOutputForRun(runBuffer, runStartAbsPos);
        token.clear();
        token.copyFrom(outputQueue.poll());
        return true;
    }

    private void buildOutputForRun(List<Token> run, int startAbsPos) {
        int n = run.size();
        if (n == 1) {
            Token t = run.get(0);
            enqueue(t.term(), startAbsPos, 1, t.startOffset(), t.endOffset(), t.type());
            return;
        }
        for (int i = 0; i < n - 1; i++) {
            Token a = run.get(i);
            Token b = run.get(i + 1);
            int p = startAbsPos + i;
            if (outputUnigrams) {
                enqueue(a.term(), p, 1, a.startOffset(), a.endOffset(), a.type());
            }
            enqueue(a.term() + b.term(), p, 2, a.startOffset(), b.endOffset(), "<DOUBLE>");
        }
        if (outputUnigrams) {
            Token last = run.get(n - 1);
            int p = startAbsPos + n - 1;
            enqueue(last.term(), p, 1, last.startOffset(), last.endOffset(), last.type());
        }
    }

    private void enqueue(String term, int pos, int positionLength, int start, int end, String type) {
        Token t = new Token();
        t.setTerm(term);
        t.setOffset(start, end);
        t.setPositionLength(positionLength);
        t.setPositionIncrement(Math.max(0, pos - lastEmittedAbsPos));
        t.setType(type);
        lastEmittedAbsPos = pos;
        outputQueue.add(t);
    }

    private String classify(Token t) {
        if (t.length() == 0) {
            return null;
        }
        int cp = Character.codePointAt(t.buffer(), 0);
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        if (han && script == Character.UnicodeScript.HAN) {
            return "han";
        }
        if (hiragana && script == Character.UnicodeScript.HIRAGANA) {
            return "hiragana";
        }
        if (katakana && script == Character.UnicodeScript.KATAKANA) {
            return "katakana";
        }
        if (hangul && script == Character.UnicodeScript.HANGUL) {
            return "hangul";
        }
        return null;
    }
}
