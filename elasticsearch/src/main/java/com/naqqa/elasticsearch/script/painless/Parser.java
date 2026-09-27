package com.naqqa.elasticsearch.script.painless;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Parser {

    private static final Set<String> PRIMITIVE_TYPES = Set.of(
        "def", "int", "long", "float", "double", "boolean", "byte", "short", "char", "String", "void"
    );

    private final List<Token> tokens;
    private int p;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Ast.Source parse(String source) {
        Parser parser = new Parser(new Lexer(source).tokenize());
        return parser.parseSource();
    }

    private Token peek() {
        return tokens.get(p);
    }

    private Token peek(int ahead) {
        int idx = Math.min(p + ahead, tokens.size() - 1);
        return tokens.get(idx);
    }

    private Token advance() {
        Token t = tokens.get(p);
        if (t.type() != TokenType.EOF) {
            p++;
        }
        return t;
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private boolean checkKeyword(String text) {
        return peek().type() == TokenType.KEYWORD && peek().text().equals(text);
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenType type, String what) {
        if (!check(type)) {
            throw new PainlessParseException("expected " + what + " but found [" + peek().text() + "]", peek().pos());
        }
        return advance();
    }

    private int mark() {
        return p;
    }

    private void reset(int mark) {
        p = mark;
    }

    public Ast.Source parseSource() {
        List<Ast.Stmt.FunctionDecl> functions = new ArrayList<>();
        List<Ast.Stmt> statements = new ArrayList<>();
        while (!check(TokenType.EOF)) {
            if (isFunctionDeclStart()) {
                functions.add(parseFunctionDecl());
            } else {
                statements.add(parseStatement());
            }
        }
        return new Ast.Source(functions, statements);
    }

    private boolean isTypeNameStart() {
        if (check(TokenType.KEYWORD) && PRIMITIVE_TYPES.contains(peek().text())) {
            return true;
        }
        return check(TokenType.IDENTIFIER);
    }

    private String tryParseTypeName() {
        if (!isTypeNameStart()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(advance().text());
        while (check(TokenType.LBRACKET) && peek(1).type() == TokenType.RBRACKET) {
            advance();
            advance();
            sb.append("[]");
        }
        return sb.toString();
    }

    private boolean isFunctionDeclStart() {
        int m = mark();
        try {
            String type = tryParseTypeName();
            if (type == null || !check(TokenType.IDENTIFIER)) {
                return false;
            }
            advance();
            boolean result = check(TokenType.LPAREN);
            return result;
        } finally {
            reset(m);
        }
    }

    private boolean isVarDeclStart() {
        if (checkKeyword("def")) {
            return true;
        }
        int m = mark();
        try {
            String type = tryParseTypeName();
            if (type == null || !check(TokenType.IDENTIFIER)) {
                return false;
            }
            advance();
            return check(TokenType.ASSIGN) || check(TokenType.SEMI) || check(TokenType.COMMA);
        } finally {
            reset(m);
        }
    }

    private Ast.Stmt.FunctionDecl parseFunctionDecl() {
        int pos = peek().pos();
        String returnType = tryParseTypeName();
        String name = expect(TokenType.IDENTIFIER, "function name").text();
        expect(TokenType.LPAREN, "(");
        List<Ast.Stmt.Param> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            do {
                String ptype = tryParseTypeName();
                if (ptype == null) {
                    throw new PainlessParseException("expected parameter type", peek().pos());
                }
                String pname = expect(TokenType.IDENTIFIER, "parameter name").text();
                params.add(new Ast.Stmt.Param(ptype, pname));
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RPAREN, ")");
        Ast.Stmt body = parseBlock();
        return new Ast.Stmt.FunctionDecl(returnType, name, params, body);
    }

    private Ast.Stmt parseStatement() {
        if (check(TokenType.LBRACE)) {
            return parseBlock();
        }
        if (checkKeyword("if")) {
            return parseIf();
        }
        if (checkKeyword("while")) {
            return parseWhile();
        }
        if (checkKeyword("do")) {
            return parseDoWhile();
        }
        if (checkKeyword("for")) {
            return parseFor();
        }
        if (checkKeyword("break")) {
            advance();
            match(TokenType.SEMI);
            return new Ast.Stmt.Break();
        }
        if (checkKeyword("continue")) {
            advance();
            match(TokenType.SEMI);
            return new Ast.Stmt.Continue();
        }
        if (checkKeyword("return")) {
            advance();
            Ast.Expr value = check(TokenType.SEMI) || check(TokenType.RBRACE) ? null : parseExpression();
            match(TokenType.SEMI);
            return new Ast.Stmt.Return(value);
        }
        if (checkKeyword("try")) {
            return parseTryCatch();
        }
        if (checkKeyword("throw")) {
            advance();
            Ast.Expr value = parseExpression();
            match(TokenType.SEMI);
            return new Ast.Stmt.Throw(value);
        }
        if (isVarDeclStart()) {
            Ast.Stmt decl = parseVarDecl();
            match(TokenType.SEMI);
            return decl;
        }
        Ast.Expr expr = parseExpression();
        match(TokenType.SEMI);
        return new Ast.Stmt.ExprStmt(expr);
    }

    private Ast.Stmt.Block parseBlock() {
        expect(TokenType.LBRACE, "{");
        List<Ast.Stmt> statements = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            statements.add(parseStatement());
        }
        expect(TokenType.RBRACE, "}");
        return new Ast.Stmt.Block(statements);
    }

    private Ast.Stmt parseSingleOrBlock() {
        if (check(TokenType.LBRACE)) {
            return parseBlock();
        }
        return parseStatement();
    }

    private Ast.Stmt.If parseIf() {
        advance();
        expect(TokenType.LPAREN, "(");
        Ast.Expr cond = parseExpression();
        expect(TokenType.RPAREN, ")");
        Ast.Stmt thenStmt = parseSingleOrBlock();
        Ast.Stmt elseStmt = null;
        if (checkKeyword("else")) {
            advance();
            elseStmt = parseSingleOrBlock();
        }
        return new Ast.Stmt.If(cond, thenStmt, elseStmt);
    }

    private Ast.Stmt.While parseWhile() {
        advance();
        expect(TokenType.LPAREN, "(");
        Ast.Expr cond = parseExpression();
        expect(TokenType.RPAREN, ")");
        Ast.Stmt body = parseSingleOrBlock();
        return new Ast.Stmt.While(cond, body);
    }

    private Ast.Stmt.DoWhile parseDoWhile() {
        advance();
        Ast.Stmt body = parseSingleOrBlock();
        if (!checkKeyword("while")) {
            throw new PainlessParseException("expected 'while' after do block", peek().pos());
        }
        advance();
        expect(TokenType.LPAREN, "(");
        Ast.Expr cond = parseExpression();
        expect(TokenType.RPAREN, ")");
        match(TokenType.SEMI);
        return new Ast.Stmt.DoWhile(body, cond);
    }

    private Ast.Stmt parseFor() {
        advance();
        expect(TokenType.LPAREN, "(");
        int m = mark();
        String type = tryParseTypeName();
        if (type != null && check(TokenType.IDENTIFIER)) {
            String name = peek().text();
            int afterName = p + 1;
            Token after = tokens.get(Math.min(afterName, tokens.size() - 1));
            if (after.type() == TokenType.COLON || (after.type() == TokenType.KEYWORD && after.text().equals("in"))) {
                advance();
                advance();
                Ast.Expr iterable = parseExpression();
                expect(TokenType.RPAREN, ")");
                Ast.Stmt body = parseSingleOrBlock();
                return new Ast.Stmt.ForEach(type, name, iterable, body);
            }
        }
        reset(m);
        Ast.Stmt init = null;
        if (!check(TokenType.SEMI)) {
            if (isVarDeclStart()) {
                init = parseVarDecl();
            } else {
                init = new Ast.Stmt.ExprStmt(parseExpression());
            }
        }
        expect(TokenType.SEMI, ";");
        Ast.Expr cond = check(TokenType.SEMI) ? null : parseExpression();
        expect(TokenType.SEMI, ";");
        Ast.Stmt update = check(TokenType.RPAREN) ? null : new Ast.Stmt.ExprStmt(parseExpression());
        expect(TokenType.RPAREN, ")");
        Ast.Stmt body = parseSingleOrBlock();
        return new Ast.Stmt.For(init, cond, update, body);
    }

    private Ast.Stmt parseTryCatch() {
        advance();
        Ast.Stmt tryBlock = parseBlock();
        List<Ast.Stmt.CatchClause> catches = new ArrayList<>();
        while (checkKeyword("catch")) {
            advance();
            expect(TokenType.LPAREN, "(");
            String exceptionType = tryParseTypeName();
            if (exceptionType == null) {
                throw new PainlessParseException("expected exception type", peek().pos());
            }
            String varName = expect(TokenType.IDENTIFIER, "catch variable").text();
            expect(TokenType.RPAREN, ")");
            Ast.Stmt body = parseBlock();
            catches.add(new Ast.Stmt.CatchClause(exceptionType, varName, body));
        }
        if (catches.isEmpty()) {
            throw new PainlessParseException("expected 'catch' after try block", peek().pos());
        }
        return new Ast.Stmt.TryCatch(tryBlock, catches);
    }

    private Ast.Stmt.VarDecl parseVarDecl() {
        String type = tryParseTypeName();
        List<Ast.Stmt.DeclEntry> decls = new ArrayList<>();
        do {
            String name = expect(TokenType.IDENTIFIER, "variable name").text();
            Ast.Expr init = null;
            if (match(TokenType.ASSIGN)) {
                init = parseExpression();
            }
            decls.add(new Ast.Stmt.DeclEntry(name, init));
        } while (match(TokenType.COMMA));
        return new Ast.Stmt.VarDecl(type, decls);
    }

    public Ast.Expr parseExpression() {
        return parseAssignment();
    }

    private static final Set<TokenType> ASSIGN_OPS = Set.of(
        TokenType.ASSIGN, TokenType.PLUS_ASSIGN, TokenType.MINUS_ASSIGN, TokenType.STAR_ASSIGN,
        TokenType.SLASH_ASSIGN, TokenType.PERCENT_ASSIGN, TokenType.AMP_ASSIGN, TokenType.PIPE_ASSIGN,
        TokenType.CARET_ASSIGN, TokenType.SHL_ASSIGN, TokenType.SHR_ASSIGN, TokenType.USHR_ASSIGN, TokenType.ELVIS_ASSIGN
    );

    private Ast.Expr parseAssignment() {
        Ast.Expr left = parseLambdaOrTernary();
        if (ASSIGN_OPS.contains(peek().type())) {
            Token op = advance();
            Ast.Expr right = parseAssignment();
            return new Ast.Expr.Assign(left, op.text(), right, op.pos());
        }
        return left;
    }

    private Ast.Expr parseLambdaOrTernary() {
        int m = mark();
        if (check(TokenType.IDENTIFIER) && peek(1).type() == TokenType.ARROW) {
            String param = advance().text();
            advance();
            Object body = parseLambdaBody();
            return new Ast.Expr.Lambda(List.of(param), body, tokens.get(m).pos());
        }
        if (check(TokenType.LPAREN)) {
            List<String> params = tryParseLambdaParamList();
            if (params != null && check(TokenType.ARROW)) {
                advance();
                Object body = parseLambdaBody();
                return new Ast.Expr.Lambda(params, body, tokens.get(m).pos());
            }
            reset(m);
        }
        return parseTernaryOrElvis();
    }

    private List<String> tryParseLambdaParamList() {
        int m = mark();
        advance();
        List<String> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            do {
                if (!check(TokenType.IDENTIFIER)) {
                    reset(m);
                    return null;
                }
                params.add(advance().text());
            } while (match(TokenType.COMMA));
        }
        if (!check(TokenType.RPAREN)) {
            reset(m);
            return null;
        }
        advance();
        return params;
    }

    private Object parseLambdaBody() {
        if (check(TokenType.LBRACE)) {
            return parseBlock();
        }
        return parseExpression();
    }

    private Ast.Expr parseTernaryOrElvis() {
        Ast.Expr cond = parseOr();
        if (check(TokenType.ELVIS)) {
            int pos = advance().pos();
            Ast.Expr right = parseAssignment();
            return new Ast.Expr.Elvis(cond, right, pos);
        }
        if (check(TokenType.QUESTION)) {
            int pos = advance().pos();
            Ast.Expr thenExpr = parseAssignment();
            expect(TokenType.COLON, ":");
            Ast.Expr elseExpr = parseAssignment();
            return new Ast.Expr.Ternary(cond, thenExpr, elseExpr, pos);
        }
        return cond;
    }

    private Ast.Expr parseOr() {
        Ast.Expr left = parseAnd();
        while (check(TokenType.OR)) {
            int pos = advance().pos();
            left = new Ast.Expr.Logical("||", left, parseAnd(), pos);
        }
        return left;
    }

    private Ast.Expr parseAnd() {
        Ast.Expr left = parseBitOr();
        while (check(TokenType.AND)) {
            int pos = advance().pos();
            left = new Ast.Expr.Logical("&&", left, parseBitOr(), pos);
        }
        return left;
    }

    private Ast.Expr parseBitOr() {
        Ast.Expr left = parseBitXor();
        while (check(TokenType.PIPE)) {
            int pos = advance().pos();
            left = new Ast.Expr.Binary("|", left, parseBitXor(), pos);
        }
        return left;
    }

    private Ast.Expr parseBitXor() {
        Ast.Expr left = parseBitAnd();
        while (check(TokenType.CARET)) {
            int pos = advance().pos();
            left = new Ast.Expr.Binary("^", left, parseBitAnd(), pos);
        }
        return left;
    }

    private Ast.Expr parseBitAnd() {
        Ast.Expr left = parseEquality();
        while (check(TokenType.AMP)) {
            int pos = advance().pos();
            left = new Ast.Expr.Binary("&", left, parseEquality(), pos);
        }
        return left;
    }

    private Ast.Expr parseEquality() {
        Ast.Expr left = parseRelational();
        while (true) {
            if (check(TokenType.EQ)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary("==", left, parseRelational(), pos);
            } else if (check(TokenType.NE)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary("!=", left, parseRelational(), pos);
            } else if (check(TokenType.REF_EQ)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary("===", left, parseRelational(), pos);
            } else if (check(TokenType.REF_NE)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary("!==", left, parseRelational(), pos);
            } else if (check(TokenType.MATCH)) {
                int pos = advance().pos();
                left = new Ast.Expr.RegexMatch(left, parseRelational(), false, pos);
            } else if (check(TokenType.MATCHES)) {
                int pos = advance().pos();
                left = new Ast.Expr.RegexMatch(left, parseRelational(), true, pos);
            } else {
                break;
            }
        }
        return left;
    }

    private Ast.Expr parseRelational() {
        Ast.Expr left = parseShift();
        while (true) {
            if (check(TokenType.LT)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary("<", left, parseShift(), pos);
            } else if (check(TokenType.LE)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary("<=", left, parseShift(), pos);
            } else if (check(TokenType.GT)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary(">", left, parseShift(), pos);
            } else if (check(TokenType.GE)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary(">=", left, parseShift(), pos);
            } else if (checkKeyword("instanceof")) {
                int pos = advance().pos();
                String type = tryParseTypeName();
                if (type == null) {
                    throw new PainlessParseException("expected type after instanceof", peek().pos());
                }
                left = new Ast.Expr.InstanceOf(left, type, pos);
            } else {
                break;
            }
        }
        return left;
    }

    private Ast.Expr parseShift() {
        Ast.Expr left = parseAdditive();
        while (true) {
            if (check(TokenType.SHL)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary("<<", left, parseAdditive(), pos);
            } else if (check(TokenType.SHR)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary(">>", left, parseAdditive(), pos);
            } else if (check(TokenType.USHR)) {
                int pos = advance().pos();
                left = new Ast.Expr.Binary(">>>", left, parseAdditive(), pos);
            } else {
                break;
            }
        }
        return left;
    }

    private Ast.Expr parseAdditive() {
        Ast.Expr left = parseMultiplicative();
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            Token op = advance();
            left = new Ast.Expr.Binary(op.text(), left, parseMultiplicative(), op.pos());
        }
        return left;
    }

    private Ast.Expr parseMultiplicative() {
        Ast.Expr left = parseUnary();
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            Token op = advance();
            left = new Ast.Expr.Binary(op.text(), left, parseUnary(), op.pos());
        }
        return left;
    }

    private Ast.Expr parseUnary() {
        if (check(TokenType.NOT) || check(TokenType.TILDE) || check(TokenType.MINUS) || check(TokenType.PLUS)) {
            Token op = advance();
            return new Ast.Expr.Unary(op.text(), parseUnary(), op.pos());
        }
        if (check(TokenType.INCR) || check(TokenType.DECR)) {
            Token op = advance();
            Ast.Expr target = parseUnary();
            return new Ast.Expr.IncDec(target, op.type() == TokenType.INCR, true, op.pos());
        }
        if (check(TokenType.LPAREN) && looksLikeCast()) {
            int pos = advance().pos();
            String type = tryParseTypeName();
            expect(TokenType.RPAREN, ")");
            Ast.Expr target = parseUnary();
            return new Ast.Expr.Cast(type, target, pos);
        }
        return parsePostfix();
    }

    private boolean looksLikeCast() {
        int m = mark();
        try {
            advance();
            String type = tryParseTypeName();
            if (type == null || !check(TokenType.RPAREN)) {
                return false;
            }
            advance();
            return switch (peek().type()) {
                case IDENTIFIER, NUMBER, STRING, LPAREN, NOT, TILDE, MINUS, PLUS, INCR, DECR -> true;
                case KEYWORD -> peek().text().equals("true") || peek().text().equals("false")
                    || peek().text().equals("null") || peek().text().equals("new") || peek().text().equals("this");
                default -> false;
            };
        } finally {
            reset(m);
        }
    }

    private Ast.Expr parsePostfix() {
        Ast.Expr expr = parsePrimary();
        while (true) {
            if (check(TokenType.DOT) || check(TokenType.QDOT)) {
                boolean nullSafe = check(TokenType.QDOT);
                int pos = advance().pos();
                String name = expect(TokenType.IDENTIFIER, "member name").text();
                if (check(TokenType.LPAREN)) {
                    advance();
                    List<Ast.Expr> args = parseArgs();
                    expect(TokenType.RPAREN, ")");
                    expr = new Ast.Expr.Call(expr, name, args, nullSafe, pos);
                } else {
                    expr = new Ast.Expr.FieldAccess(expr, name, nullSafe, pos);
                }
            } else if (check(TokenType.LBRACKET)) {
                int pos = advance().pos();
                Ast.Expr index = parseExpression();
                expect(TokenType.RBRACKET, "]");
                expr = new Ast.Expr.Index(expr, index, false, pos);
            } else if (check(TokenType.DOUBLE_COLON)) {
                int pos = advance().pos();
                String method = check(TokenType.KEYWORD) && peek().text().equals("new") ? advance().text() : expect(TokenType.IDENTIFIER, "method reference").text();
                expr = new Ast.Expr.MethodRef(nameOf(expr), method, pos);
            } else if (check(TokenType.INCR) || check(TokenType.DECR)) {
                Token op = advance();
                expr = new Ast.Expr.IncDec(expr, op.type() == TokenType.INCR, false, op.pos());
            } else {
                break;
            }
        }
        return expr;
    }

    private String nameOf(Ast.Expr expr) {
        if (expr instanceof Ast.Expr.Name n) {
            return n.name();
        }
        throw new PainlessParseException("expected a type or variable name before ::", expr.pos());
    }

    private List<Ast.Expr> parseArgs() {
        List<Ast.Expr> args = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            do {
                args.add(parseExpression());
            } while (match(TokenType.COMMA));
        }
        return args;
    }

    private Ast.Expr parsePrimary() {
        Token t = peek();
        if (t.type() == TokenType.NUMBER) {
            advance();
            return new Ast.Expr.NumberLit(t.literal(), t.pos());
        }
        if (t.type() == TokenType.STRING) {
            advance();
            return new Ast.Expr.StringLit((String) t.literal(), t.pos());
        }
        if (t.type() == TokenType.REGEX) {
            advance();
            String[] pf = (String[]) t.literal();
            return new Ast.Expr.RegexLit(pf[0], pf[1], t.pos());
        }
        if (checkKeyword("true")) {
            advance();
            return new Ast.Expr.BoolLit(true, t.pos());
        }
        if (checkKeyword("false")) {
            advance();
            return new Ast.Expr.BoolLit(false, t.pos());
        }
        if (checkKeyword("null")) {
            advance();
            return new Ast.Expr.NullLit(t.pos());
        }
        if (checkKeyword("this")) {
            advance();
            return new Ast.Expr.Name("this", t.pos());
        }
        if (checkKeyword("new")) {
            return parseNew();
        }
        if (t.type() == TokenType.LPAREN) {
            advance();
            Ast.Expr expr = parseExpression();
            expect(TokenType.RPAREN, ")");
            return expr;
        }
        if (t.type() == TokenType.LBRACKET) {
            return parseListOrMapInit();
        }
        if (t.type() == TokenType.IDENTIFIER) {
            advance();
            if (check(TokenType.LPAREN)) {
                advance();
                List<Ast.Expr> args = parseArgs();
                expect(TokenType.RPAREN, ")");
                return new Ast.Expr.FunctionCall(t.text(), args, t.pos());
            }
            return new Ast.Expr.Name(t.text(), t.pos());
        }
        throw new PainlessParseException("unexpected token [" + t.text() + "]", t.pos());
    }

    private Ast.Expr parseListOrMapInit() {
        int pos = advance().pos();
        if (check(TokenType.COLON)) {
            advance();
            expect(TokenType.RBRACKET, "]");
            return new Ast.Expr.MapInit(List.of(), pos);
        }
        if (check(TokenType.RBRACKET)) {
            advance();
            return new Ast.Expr.ListInit(List.of(), pos);
        }
        Ast.Expr first = parseExpression();
        if (check(TokenType.COLON)) {
            advance();
            Ast.Expr firstValue = parseExpression();
            List<Ast.Expr.MapEntry> entries = new ArrayList<>();
            entries.add(new Ast.Expr.MapEntry(first, firstValue));
            while (match(TokenType.COMMA)) {
                Ast.Expr key = parseExpression();
                expect(TokenType.COLON, ":");
                Ast.Expr value = parseExpression();
                entries.add(new Ast.Expr.MapEntry(key, value));
            }
            expect(TokenType.RBRACKET, "]");
            return new Ast.Expr.MapInit(entries, pos);
        }
        List<Ast.Expr> elements = new ArrayList<>();
        elements.add(first);
        while (match(TokenType.COMMA)) {
            elements.add(parseExpression());
        }
        expect(TokenType.RBRACKET, "]");
        return new Ast.Expr.ListInit(elements, pos);
    }

    private Ast.Expr parseNew() {
        int pos = advance().pos();
        if (!isTypeNameStart()) {
            throw new PainlessParseException("expected type after 'new'", peek().pos());
        }
        String type = advance().text();
        if (check(TokenType.LBRACKET)) {
            List<Ast.Expr> dims = new ArrayList<>();
            int extra = 0;
            while (check(TokenType.LBRACKET)) {
                advance();
                if (check(TokenType.RBRACKET)) {
                    advance();
                    extra++;
                } else {
                    dims.add(parseExpression());
                    expect(TokenType.RBRACKET, "]");
                }
            }
            if (check(TokenType.LBRACE)) {
                advance();
                List<Ast.Expr> elements = new ArrayList<>();
                if (!check(TokenType.RBRACE)) {
                    do {
                        elements.add(parseExpression());
                    } while (match(TokenType.COMMA));
                }
                expect(TokenType.RBRACE, "}");
                return new Ast.Expr.ArrayInit(type, elements, pos);
            }
            return new Ast.Expr.NewArray(type, dims, extra, pos);
        }
        expect(TokenType.LPAREN, "(");
        List<Ast.Expr> args = parseArgs();
        expect(TokenType.RPAREN, ")");
        return new Ast.Expr.NewObject(type, args, pos);
    }
}
