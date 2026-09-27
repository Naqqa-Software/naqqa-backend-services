package com.naqqa.elasticsearch.script.painless;

import com.naqqa.elasticsearch.script.CompiledScript;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptEngine;
import com.naqqa.elasticsearch.script.ScriptException;
import com.naqqa.elasticsearch.script.ScriptSettings;

import java.util.List;
import java.util.Map;

public final class PainlessScriptEngine implements ScriptEngine {

    private final ScriptSettings settings;

    public PainlessScriptEngine() {
        this(ScriptSettings.defaults());
    }

    public PainlessScriptEngine(ScriptSettings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return "painless";
    }

    @Override
    public CompiledScript compile(String name, String source, ScriptContext context, Map<String, String> options) {
        Ast.Source ast;
        try {
            ast = Parser.parse(source);
        } catch (PainlessParseException e) {
            throw new ScriptException("compile error", e, List.of(), source, type(), new ScriptException.Position(e.pos(), e.pos(), e.pos()));
        }
        try {
            new TypeChecker(ast, context).check();
        } catch (PainlessTypeException | PainlessSecurityException e) {
            throw new ScriptException("compile error", e, List.of(), source, type());
        }
        if (settings.bytecodeEnabled() && BytecodeCompiler.canCompile(ast)) {
            try {
                BytecodeCompiler.CompiledResult result = BytecodeCompiler.compile(ast, settings.maxLoopCounter());
                return new BytecodeCompiledScript(result.executable());
            } catch (Throwable ignored) {
            }
        }
        return new PainlessCompiledScript(new Interpreter(ast, context, settings));
    }

    @Override
    public boolean supports(ScriptContext context) {
        return true;
    }
}
