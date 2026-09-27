package com.naqqa.elasticsearch.script.expression;

import com.naqqa.elasticsearch.script.ScriptDocValues;

import java.time.ZonedDateTime;
import java.util.List;

public final class ExpressionCompiler {

    private ExpressionCompiler() {
    }

    public static ExpressionEvaluator compile(ExpressionAst.Node node) {
        return switch (node) {
            case ExpressionAst.Node.Num n -> ctx -> n.value();
            case ExpressionAst.Node.Score n -> ExpressionContext::score;
            case ExpressionAst.Node.ParamRef n -> {
                String name = n.name();
                yield ctx -> {
                    Object v = ctx.param(name);
                    if (v == null) {
                        throw new IllegalArgumentException("Parameter [" + name + "] was not provided");
                    }
                    if (!(v instanceof Number num)) {
                        throw new IllegalArgumentException("Parameter [" + name + "] is not numeric");
                    }
                    return num.doubleValue();
                };
            }
            case ExpressionAst.Node.DocValue n -> compileDocValue(n);
            case ExpressionAst.Node.FuncCall n -> compileFunc(n);
            case ExpressionAst.Node.Unary n -> {
                ExpressionEvaluator operand = compile(n.operand());
                yield n.op() == '-' ? ctx -> -operand.evaluate(ctx) : ctx -> operand.evaluate(ctx) == 0.0 ? 1.0 : 0.0;
            }
            case ExpressionAst.Node.Binary n -> compileBinary(n);
            case ExpressionAst.Node.Logical n -> compileLogical(n);
            case ExpressionAst.Node.Ternary n -> {
                ExpressionEvaluator cond = compile(n.cond());
                ExpressionEvaluator thenEval = compile(n.thenNode());
                ExpressionEvaluator elseEval = compile(n.elseNode());
                yield ctx -> cond.evaluate(ctx) != 0.0 ? thenEval.evaluate(ctx) : elseEval.evaluate(ctx);
            }
        };
    }

    private static ExpressionEvaluator compileLogical(ExpressionAst.Node.Logical n) {
        ExpressionEvaluator left = compile(n.left());
        ExpressionEvaluator right = compile(n.right());
        if (n.op().equals("&&")) {
            return ctx -> (left.evaluate(ctx) != 0.0 && right.evaluate(ctx) != 0.0) ? 1.0 : 0.0;
        }
        return ctx -> (left.evaluate(ctx) != 0.0 || right.evaluate(ctx) != 0.0) ? 1.0 : 0.0;
    }

    private static ExpressionEvaluator compileBinary(ExpressionAst.Node.Binary n) {
        ExpressionEvaluator l = compile(n.left());
        ExpressionEvaluator r = compile(n.right());
        return switch (n.op()) {
            case "+" -> ctx -> l.evaluate(ctx) + r.evaluate(ctx);
            case "-" -> ctx -> l.evaluate(ctx) - r.evaluate(ctx);
            case "*" -> ctx -> l.evaluate(ctx) * r.evaluate(ctx);
            case "/" -> ctx -> l.evaluate(ctx) / r.evaluate(ctx);
            case "%" -> ctx -> l.evaluate(ctx) % r.evaluate(ctx);
            case "<" -> ctx -> l.evaluate(ctx) < r.evaluate(ctx) ? 1.0 : 0.0;
            case "<=" -> ctx -> l.evaluate(ctx) <= r.evaluate(ctx) ? 1.0 : 0.0;
            case ">" -> ctx -> l.evaluate(ctx) > r.evaluate(ctx) ? 1.0 : 0.0;
            case ">=" -> ctx -> l.evaluate(ctx) >= r.evaluate(ctx) ? 1.0 : 0.0;
            case "==" -> ctx -> l.evaluate(ctx) == r.evaluate(ctx) ? 1.0 : 0.0;
            case "!=" -> ctx -> l.evaluate(ctx) != r.evaluate(ctx) ? 1.0 : 0.0;
            default -> throw new ExpressionParseException("unknown operator [" + n.op() + "]");
        };
    }

    private static double numeric(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof ZonedDateTime z) {
            return z.toInstant().toEpochMilli();
        }
        throw new IllegalArgumentException("cannot convert [" + value + "] to a number");
    }

    private static ExpressionEvaluator compileDocValue(ExpressionAst.Node.DocValue n) {
        String field = n.field();
        return switch (n.accessor()) {
            case "value" -> ctx -> numeric(ctx.doc().get(field).getValue());
            case "length" -> ctx -> ctx.doc().get(field).size();
            case "empty" -> ctx -> ctx.doc().get(field).isEmpty() ? 1.0 : 0.0;
            case "count" -> ctx -> ctx.doc().get(field).size();
            case "sum" -> ctx -> sumOf(ctx.doc().get(field));
            case "avg" -> ctx -> {
                ScriptDocValues<?> dv = ctx.doc().get(field);
                return dv.isEmpty() ? 0.0 : sumOf(dv) / dv.size();
            };
            case "min" -> ctx -> {
                double min = Double.POSITIVE_INFINITY;
                for (Object v : ctx.doc().get(field).getValues()) {
                    min = Math.min(min, numeric(v));
                }
                return min;
            };
            case "max" -> ctx -> {
                double max = Double.NEGATIVE_INFINITY;
                for (Object v : ctx.doc().get(field).getValues()) {
                    max = Math.max(max, numeric(v));
                }
                return max;
            };
            default -> throw new ExpressionParseException("unknown accessor [" + n.accessor() + "]");
        };
    }

    private static double sumOf(ScriptDocValues<?> dv) {
        double s = 0;
        for (Object v : dv.getValues()) {
            s += numeric(v);
        }
        return s;
    }

    private static ExpressionEvaluator compileFunc(ExpressionAst.Node.FuncCall n) {
        List<ExpressionEvaluator> args = n.args().stream().map(ExpressionCompiler::compile).toList();
        return switch (n.name() + "/" + args.size()) {
            case "sqrt/1" -> ctx -> Math.sqrt(args.get(0).evaluate(ctx));
            case "ln/1" -> ctx -> Math.log(args.get(0).evaluate(ctx));
            case "log10/1" -> ctx -> Math.log10(args.get(0).evaluate(ctx));
            case "exp/1" -> ctx -> Math.exp(args.get(0).evaluate(ctx));
            case "abs/1" -> ctx -> Math.abs(args.get(0).evaluate(ctx));
            case "floor/1" -> ctx -> Math.floor(args.get(0).evaluate(ctx));
            case "ceil/1" -> ctx -> Math.ceil(args.get(0).evaluate(ctx));
            case "sin/1" -> ctx -> Math.sin(args.get(0).evaluate(ctx));
            case "cos/1" -> ctx -> Math.cos(args.get(0).evaluate(ctx));
            case "tan/1" -> ctx -> Math.tan(args.get(0).evaluate(ctx));
            case "asin/1" -> ctx -> Math.asin(args.get(0).evaluate(ctx));
            case "acos/1" -> ctx -> Math.acos(args.get(0).evaluate(ctx));
            case "atan/1" -> ctx -> Math.atan(args.get(0).evaluate(ctx));
            case "pow/2" -> ctx -> Math.pow(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx));
            case "atan2/2" -> ctx -> Math.atan2(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx));
            case "min/2" -> ctx -> Math.min(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx));
            case "max/2" -> ctx -> Math.max(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx));
            case "haversin/4" -> ctx -> haversinKm(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx), args.get(2).evaluate(ctx), args.get(3).evaluate(ctx));
            default -> throw new ExpressionParseException("Unrecognized function call [" + n.name() + "] with [" + args.size() + "] arguments");
        };
    }

    private static double haversinKm(double lat1, double lon1, double lat2, double lon2) {
        return com.naqqa.elasticsearch.script.GeoPoint.haversineMeters(lat1, lon1, lat2, lon2) / 1000.0;
    }
}
