package org.telegram.messenger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/** Bounded arithmetic parser for draft suggestions; never evaluates executable code. */
public final class GhostCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private final String input;
    private int position, depth, operations;
    private GhostCalculator(String input) { this.input = input; }
    /** Evaluate the complete trailing expression, preserving any prose before it.
     * Never salvage a valid subexpression out of a malformed arithmetic suffix. */
    public static String calculateDraft(String text) {
        if (text == null || text.isEmpty()) return null;
        if (text.length() <= 512) {
            String entire = calculate(text);
            if (entire != null) return entire;
        }
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        int start = end;
        while (start > 0) {
            if (end - start >= 512) return null;
            char c=text.charAt(start-1);
            if(c>='a'&&c<='z'||c>='A'&&c<='Z'){
                int word=start;while(word>0&&isAsciiLetter(text.charAt(word-1)))word--;
                String name=text.substring(word,start).toLowerCase(Locale.ROOT);
                if(!function(name)&&!name.equals("x"))break;
                start=word;
            }else if(draftMathCharacter(c))start--;else break;
        }
        // A word ending in x ("max 2+2") or a prose colon ("Total: 2+2")
        // must not become a multiplication/division operator in the expression.
        if (start > 0 && Character.isLetter(text.charAt(start - 1))) {
            while (start < end && (text.charAt(start) == 'x' || text.charAt(start) == 'X')) start++;
            if (start < end && text.charAt(start) == ':') start++;
            if (start + 1 < end && (text.charAt(start) == '.' || text.charAt(start) == ',')
                    && Character.isWhitespace(text.charAt(start + 1))) start++;
        }
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        if (start == end) return null;
        if (start > 0) {
            char previous = text.charAt(start - 1);
            if (Character.isLetterOrDigit(previous) || previous == '_' || previous == '=') return null;
        }
        return calculate(text.substring(start, end));
    }
    private static boolean draftMathCharacter(char c) {
        return c >= '0' && c <= '9' || Character.isWhitespace(c) || ".,()+-−*/:÷×xX^%°".indexOf(c) >= 0;
    }
    private static boolean isAsciiLetter(char c){return c>='a'&&c<='z'||c>='A'&&c<='Z';}
    private static boolean function(String name){return name.equals("sin")||name.equals("cos")||name.equals("tan")||name.equals("cot")||name.equals("tg")||name.equals("ctg");}
    public static String calculate(String text) {
        if (text != null && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.matches(".*[0-9]\\s+[0-9].*"))) return null;
        if (text == null || text.length() > 512 || !text.matches("[0-9a-zA-Z\\s.,()+\\-−*/:÷×xX^%°]+")) return null;
        String expression = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replace(',', '.').replace('−', '-').replace('÷', '/')
            .replace(':', '/').replace('×', '*').replace('x', '*').replace('X', '*').replace("**", "^");
        try {
            GhostCalculator parser = new GhostCalculator(expression);
            BigDecimal result = parser.sum();
            if (parser.position != expression.length() || parser.operations == 0 || result.precision() > 120 || Math.abs(result.scale()) > 1000) return null;
            result = result.round(new MathContext(14)).stripTrailingZeros();
            if (result.signum() == 0) return "0";
            return result.scale() < -30 || result.scale() > 30 ? result.toEngineeringString() : result.toPlainString();
        } catch (RuntimeException invalid) { return null; }
    }
    private boolean take(char c) {
        if (position < input.length() && input.charAt(position) == c) { position++; return true; }
        return false;
    }
    private void operation() { if (++operations > 128) throw new IllegalArgumentException(); }
    private BigDecimal sum() {
        BigDecimal value = product();
        while (true) {
            if (take('+')) { operation(); value = value.add(product(), MC); }
            else if (take('-')) { operation(); value = value.subtract(product(), MC); }
            else return value;
        }
    }
    private BigDecimal product() {
        BigDecimal value = unary();
        while (true) {
            if (take('*')) { operation(); value = value.multiply(unary(), MC); }
            else if (take('/')) { operation(); value = value.divide(unary(), MC); }
            else return value;
        }
    }
    private BigDecimal unary() {
        if (++depth > 32) throw new IllegalArgumentException();
        try {
            if (take('+')) return unary();
            if (take('-')) return unary().negate();
            return power();
        } finally { depth--; }
    }
    private BigDecimal power() {
        BigDecimal value = atom();
        while (take('%')) { operation(); value = value.divide(BigDecimal.valueOf(100), MC); }
        if (take('^')) {
            operation(); BigDecimal exponent = unary();
            if (exponent.abs().compareTo(BigDecimal.valueOf(1000)) > 0) throw new IllegalArgumentException();
            if (exponent.stripTrailingZeros().scale() <= 0) {
                int n = exponent.intValueExact();
                value = n < 0 ? BigDecimal.ONE.divide(value.pow(-n, MC), MC) : value.pow(n, MC);
            } else {
                double result = Math.pow(value.doubleValue(), exponent.doubleValue());
                if (!Double.isFinite(result)) throw new IllegalArgumentException();
                value = BigDecimal.valueOf(result);
            }
        }
        return value;
    }
    private BigDecimal atom() {
        if(position<input.length()&&isAsciiLetter(input.charAt(position))){
            int begin=position;while(position<input.length()&&isAsciiLetter(input.charAt(position)))position++;
            String name=input.substring(begin,position);if(!function(name))throw new IllegalArgumentException();operation();
            boolean negative=take('-');if(!negative)take('+');
            BigDecimal degrees=atom();if(negative)degrees=degrees.negate();
            return trig(name,degrees);
        }
        if (take('(')) {
            BigDecimal value = sum();
            if (!take(')')) throw new IllegalArgumentException();
            take('°');
            return value;
        }
        int start = position;
        while (position < input.length() && (Character.isDigit(input.charAt(position)) || input.charAt(position) == '.')) position++;
        if (start == position) throw new IllegalArgumentException();
        BigDecimal number=new BigDecimal(input.substring(start, position), MC);take('°');return number;
    }
    private static BigDecimal trig(String name,BigDecimal angle){
        BigDecimal reduced=angle.remainder(BigDecimal.valueOf(360));double degrees=reduced.doubleValue(),radians=Math.toRadians(degrees);
        double sin=Math.sin(radians),cos=Math.cos(radians);
        if(reduced.remainder(BigDecimal.valueOf(180)).signum()==0)sin=0;
        if(reduced.abs().compareTo(BigDecimal.valueOf(90))==0||reduced.abs().compareTo(BigDecimal.valueOf(270))==0)cos=0;
        double value;
        if(name.equals("sin"))value=sin;else if(name.equals("cos"))value=cos;
        else if(name.equals("tan")||name.equals("tg")){if(cos==0)throw new ArithmeticException();value=sin/cos;}
        else {if(sin==0)throw new ArithmeticException();value=cos/sin;}
        if(!Double.isFinite(value))throw new ArithmeticException();return BigDecimal.valueOf(value);
    }
}
