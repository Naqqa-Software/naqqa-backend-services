package com.naqqa.elasticsearch.script.expression;

import java.util.List;

public final class ExpressionAst {

    private ExpressionAst() {
    }

    public sealed interface Node {
        record Num(double value) implements Node {}
        record Score() implements Node {}
        record ParamRef(String name) implements Node {}
        record DocValue(String field, String accessor) implements Node {}
        record FuncCall(String name, List<Node> args) implements Node {}
        record Unary(char op, Node operand) implements Node {}
        record Binary(String op, Node left, Node right) implements Node {}
        record Logical(String op, Node left, Node right) implements Node {}
        record Ternary(Node cond, Node thenNode, Node elseNode) implements Node {}
    }
}
