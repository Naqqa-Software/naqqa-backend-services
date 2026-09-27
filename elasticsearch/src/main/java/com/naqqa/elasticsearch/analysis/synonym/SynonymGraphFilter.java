package com.naqqa.elasticsearch.analysis.synonym;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class SynonymGraphFilter extends TokenFilter {

    private final SynonymMap map;
    private final boolean graph;
    private final List<Token> inputBuffer = new ArrayList<>();
    private final Deque<Token> outputBuffer = new ArrayDeque<>();
    private boolean inputExhausted;

    public SynonymGraphFilter(TokenStream input, SynonymMap map) {
        this(input, map, true);
    }

    protected SynonymGraphFilter(TokenStream input, SynonymMap map, boolean graph) {
        super(input);
        this.map = map;
        this.graph = graph;
    }

    @Override
    public void reset() {
        super.reset();
        inputBuffer.clear();
        outputBuffer.clear();
        inputExhausted = false;
    }

    private boolean ensureBuffered(int count) {
        while (inputBuffer.size() < count) {
            if (inputExhausted || !input.incrementToken()) {
                inputExhausted = true;
                return inputBuffer.size() >= count;
            }
            inputBuffer.add(token.copy());
        }
        return true;
    }

    @Override
    public boolean incrementToken() {
        if (!outputBuffer.isEmpty()) {
            token.clear();
            token.copyFrom(outputBuffer.poll());
            return true;
        }
        if (!ensureBuffered(1)) {
            return false;
        }
        SynonymMap.Node node = map.root;
        int matchLen = 0;
        List<List<String>> matchedOutputs = null;
        int i = 0;
        while (true) {
            if (!ensureBuffered(i + 1)) {
                break;
            }
            if (i > 0 && inputBuffer.get(i).positionIncrement() != 1) {
                break;
            }
            SynonymMap.Node next = node.child(inputBuffer.get(i).term(), false);
            if (next == null) {
                break;
            }
            node = next;
            i++;
            if (node.outputs != null) {
                matchLen = i;
                matchedOutputs = node.outputs;
            }
        }
        if (matchedOutputs == null) {
            Token t = inputBuffer.remove(0);
            token.clear();
            token.copyFrom(t);
            return true;
        }
        List<Token> consumed = new ArrayList<>(inputBuffer.subList(0, matchLen));
        inputBuffer.subList(0, matchLen).clear();
        emitAlternatives(consumed, matchedOutputs);
        token.clear();
        token.copyFrom(outputBuffer.poll());
        return true;
    }

    private void emitAlternatives(List<Token> consumed, List<List<String>> alternatives) {
        int n = consumed.size();
        int startOffset = consumed.get(0).startOffset();
        int endOffset = consumed.get(n - 1).endOffset();
        int firstPosInc = consumed.get(0).positionIncrement();
        boolean firstAlt = true;
        for (List<String> alt : alternatives) {
            for (int w = 0; w < alt.size(); w++) {
                Token t = new Token();
                t.setTerm(alt.get(w));
                t.setOffset(startOffset, endOffset);
                int posInc = w == 0 ? (firstAlt ? firstPosInc : 0) : 1;
                int posLen = (graph && w == 0 && alt.size() == 1) ? Math.max(1, n) : 1;
                t.setPositionIncrement(posInc);
                t.setPositionLength(posLen);
                outputBuffer.add(t);
            }
            firstAlt = false;
        }
    }
}
