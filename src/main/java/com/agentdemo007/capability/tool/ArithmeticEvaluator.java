package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 手写递归下降算术表达式解析器（第四层·示例工具内核）。
 *
 * <p>支持四则运算 + 括号 + 一元正负 + 区间求和 {@code sum(a..b)}，{@link BigDecimal} 高精度，
 * <b>禁用 eval / ScriptEngine</b>（§2 关键技术决策·工具安全）。仅允许数字、{@code + - * / ( ) .}、
 * {@code sum} 关键字与空白；任何其它字符（含字母注入如 {@code "System.exit(0)"}）在词法阶段即被拒绝，
 * 抛 {@link ToolRecoverableException}（LC4j {@code DefaultToolExecutor} 原生吞→消息当结果返回，
 * 不透传 ToolExecutionStep，[[langchain4j-boot4-compat-findings]]：自纠正开箱即用）。
 *
 * <p>语法：
 * <pre>
 * expr   := term (('+'|'-') term)*
 * term   := factor (('*'|'/') factor)*
 * factor := ('+'|'-') factor | primary
 * primary:= NUMBER | '(' expr ')' | sumExpr
 * sumExpr:= 'sum' '(' expr '..' expr ')'
 * </pre>
 *
 * <p>除法固定 10 位小数 HALF_UP；除零抛异常；区间端点须为整数，{@code lo > hi} 返回 0。
 */
public class ArithmeticEvaluator {

    /** 除法保留小数位（HALF_UP）。 */
    static final int DIV_SCALE = 10;

    private String input;
    private int pos;
    private Token cur;

    public BigDecimal evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ToolRecoverableException("表达式为空");
        }
        this.input = expression;
        this.pos = 0;
        this.cur = nextToken();
        BigDecimal result = parseExpr();
        if (cur.type != TokenType.EOF) {
            throw new ToolRecoverableException("多余字符: " + remaining());
        }
        return result.stripTrailingZeros();
    }

    private BigDecimal parseExpr() {
        BigDecimal value = parseTerm();
        while (cur.type == TokenType.PLUS || cur.type == TokenType.MINUS) {
            TokenType op = cur.type;
            cur = nextToken();
            BigDecimal rhs = parseTerm();
            value = (op == TokenType.PLUS) ? value.add(rhs) : value.subtract(rhs);
        }
        return value;
    }

    private BigDecimal parseTerm() {
        BigDecimal value = parseFactor();
        while (cur.type == TokenType.STAR || cur.type == TokenType.SLASH) {
            TokenType op = cur.type;
            cur = nextToken();
            BigDecimal rhs = parseFactor();
            if (op == TokenType.STAR) {
                value = value.multiply(rhs);
            } else {
                if (rhs.signum() == 0) {
                    throw new ToolRecoverableException("除零错误");
                }
                value = value.divide(rhs, DIV_SCALE, RoundingMode.HALF_UP);
            }
        }
        return value;
    }

    private BigDecimal parseFactor() {
        if (cur.type == TokenType.PLUS) {
            cur = nextToken();
            return parseFactor();
        }
        if (cur.type == TokenType.MINUS) {
            cur = nextToken();
            return parseFactor().negate();
        }
        return parsePrimary();
    }

    private BigDecimal parsePrimary() {
        if (cur.type == TokenType.NUMBER) {
            BigDecimal value = cur.value;
            cur = nextToken();
            return value;
        }
        if (cur.type == TokenType.LPAREN) {
            cur = nextToken();
            BigDecimal value = parseExpr();
            expect(TokenType.RPAREN, "缺少右括号");
            return value;
        }
        if (cur.type == TokenType.SUM) {
            return parseSumExpr();
        }
        throw new ToolRecoverableException("缺少操作数: " + remaining());
    }

    /** sum '(' expr '..' expr ')' —— 区间求和（端点须为整数，lo>hi 返回 0）。 */
    private BigDecimal parseSumExpr() {
        cur = nextToken(); // 消费 SUM
        expect(TokenType.LPAREN, "sum 后须为左括号");
        BigDecimal loBd = parseExpr();
        expect(TokenType.DOTDOT, "区间求和须以 '..' 分隔端点");
        BigDecimal hiBd = parseExpr();
        expect(TokenType.RPAREN, "缺少右括号");
        long lo = toInt(loBd, "区间端点必须为整数");
        long hi = toInt(hiBd, "区间端点必须为整数");
        if (lo > hi) {
            return BigDecimal.ZERO;
        }
        BigInteger loB = BigInteger.valueOf(lo);
        BigInteger hiB = BigInteger.valueOf(hi);
        BigInteger count = hiB.subtract(loB).add(BigInteger.ONE);
        BigInteger total = loB.add(hiB).multiply(count).divide(BigInteger.valueOf(2L));
        return new BigDecimal(total);
    }

    private void expect(TokenType type, String message) {
        if (cur.type != type) {
            throw new ToolRecoverableException(message + "（得到 " + cur.type + "）");
        }
        cur = nextToken();
    }

    private static long toInt(BigDecimal value, String message) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new ToolRecoverableException(message);
        }
    }

    private String remaining() {
        int end = Math.min(pos, input.length());
        return input.substring(end);
    }

    // ---- 词法 ----

    private Token nextToken() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
        if (pos >= input.length()) {
            return new Token(TokenType.EOF, null);
        }
        char c = input.charAt(pos);
        if (Character.isDigit(c)) {
            return readNumber();
        }
        return switch (c) {
            case '+' -> single(TokenType.PLUS);
            case '-' -> single(TokenType.MINUS);
            case '*' -> single(TokenType.STAR);
            case '/' -> single(TokenType.SLASH);
            case '(' -> single(TokenType.LPAREN);
            case ')' -> single(TokenType.RPAREN);
            case '.' -> readDotOrDotDot();
            default -> {
                if (Character.isLetter(c)) {
                    yield readWord();
                }
                throw new ToolRecoverableException("非法字符: '" + c + "'");
            }
        };
    }

    private Token readNumber() {
        int start = pos;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        // 小数点：仅当下一个字符不是 '.'（避免吞掉 '..'）时按小数处理
        if (pos < input.length() && input.charAt(pos) == '.'
                && !(pos + 1 < input.length() && input.charAt(pos + 1) == '.')) {
            pos++; // 消费 '.'
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        return new Token(TokenType.NUMBER, new BigDecimal(input.substring(start, pos)));
    }

    private Token readDotOrDotDot() {
        if (pos + 1 < input.length() && input.charAt(pos + 1) == '.') {
            pos += 2;
            return new Token(TokenType.DOTDOT, null);
        }
        throw new ToolRecoverableException("非法字符: '.'");
    }

    private Token readWord() {
        int start = pos;
        while (pos < input.length() && Character.isLetter(input.charAt(pos))) {
            pos++;
        }
        String word = input.substring(start, pos);
        if (word.equalsIgnoreCase("sum")) {
            return new Token(TokenType.SUM, null);
        }
        throw new ToolRecoverableException("非法标识符: " + word);
    }

    private Token single(TokenType type) {
        pos++;
        return new Token(type, null);
    }

    private record Token(TokenType type, BigDecimal value) {
    }

    private enum TokenType {
        NUMBER, PLUS, MINUS, STAR, SLASH, LPAREN, RPAREN, DOTDOT, SUM, EOF
    }
}
