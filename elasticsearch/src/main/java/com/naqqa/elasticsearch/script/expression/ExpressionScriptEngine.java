package com.naqqa.elasticsearch.script.expression;

import com.naqqa.elasticsearch.script.CompiledScript;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptEngine;
import com.naqqa.elasticsearch.script.ScriptException;

import java.util.List;
import java.util.Map;

public final class ExpressionScriptEngine implements ScriptEngine {

    @Override
    public String type() {
        return "expression";
    }

    @Override
    public CompiledScript compile(String name, String source, ScriptContext context, Map<String, String> options) {
        ExpressionAst.Node ast;
        try {
            ast = ExpressionParser.parse(source);
        } catch (ExpressionParseException e) {
            throw new ScriptException("compile error", e, List.of(), source, type());
        }
        ExpressionEvaluator evaluator = ExpressionCompiler.compile(ast);
        return new ExpressionScript(evaluator);
    }

    @Override
    public boolean supports(ScriptContext context) {
        return context.returnType() == ScriptContext.ReturnType.DOUBLE || context.returnType() == ScriptContext.ReturnType.OBJECT
            || context.returnType() == ScriptContext.ReturnType.BOOLEAN;
    }
}
