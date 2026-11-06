"""Exercise the production arithmetic parser on the host JVM."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / "TMessagesProj/src/main/java/org/telegram/messenger/GhostCalculator.java"
test = r'''
import org.telegram.messenger.GhostCalculator;
public class CalculatorCheck {
    static void check(String expression, String expected) {
        String actual = GhostCalculator.calculate(expression);
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(expression + ": " + actual + " != " + expected);
    }
    static void draft(String text, String expected) {
        String actual = GhostCalculator.calculateDraft(text);
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("Draft: " + text.substring(0, Math.min(100, text.length())) + ": " + actual + " != " + expected);
    }
    public static void main(String[] args) {
        check("2+2", "4"); check("2 + 2", "4"); check("2*2", "4");
        check("2^3", "8"); check("2**3", "8"); check("2/2", "1");
        check("2 : 2", "1"); check("2:2", "1"); check("2\u00d72", "4");
        check("2\u00f72", "1"); check("2x2", "4"); check("2X2", "4");
        check("2+3*4", "14"); check("(2+3)*4", "20"); check("2^3^2", "512");
        check("-2^2", "-4"); check("(-2)^2", "4"); check("2^-3", "0.125");
        check("0.1+0.2", "0.3"); check("2,5+0,5", "3"); check("50%", "0.5");
        check("200*10%", "20"); check("9^0.5", "3"); check("2-2", "0");
        for(String invalid : new String[]{"", "2", "hi", "https://2:2", "2+", "2/0", "2+2 = 4", "2..2+2", "(2+2", "1e10", "2^1001", "1;print(1)", "__import__('os')", "2**", "2 2", "2 2+2", "2+2\n3+3"}) check(invalid, null);
        check("(".repeat(40)+"1+1"+")".repeat(40), null);
        check("2+".repeat(400)+"2", null);
        draft("Привет, как дела? 2 + 2", "4");
        draft("Привет, как дела? 2+2", "4");
        draft("Стоимость: 2*(3+4)", "14");
        draft("Итого:2^3", "8");
        draft("max 2+2", "4");
        draft("Сколько? -2^2", "-4");
        draft("Расчёт\n2 : 2", "1");
        draft("Расчёт: 2,5 + 0,5", "3");
        draft("Один пример 2+2 и другой 3*3", "9");
        draft("2+2", "4");
        draft("Привет! 2+2 = 4", null);
        draft("Привет! 2+", null);
        draft("Привет! 2/0", null);
        draft("Привет! 2 2+2", null);
        draft("Привет! 2+2\n3+3", null);
        draft("https://2:2", null);
        draft("https://host/2+2", null);
        draft("https://host/path2+2", null);
        draft("abc2+2", null);
        draft("Привет 42", null);
        draft("Текст "+"2+".repeat(400)+"2", null);
        draft("Длинное сообщение. ".repeat(1000)+"2 + 2", "4");
        check("sin60", "0.86602540378444"); check("cos14", "0.970295726276");
        check("sin(30+30)", "0.86602540378444"); check("SIN 30", "0.5");
        check("sin-30", "-0.5"); check("cos180", "-1"); check("sin360", "0");
        check("sin(60)^2+cos(60)^2", "1"); check("sin60^2", "0.75");
        check("tg45", "1"); check("tan(45)", "1"); check("ctg45", "1"); check("cot90", "0");
        check("tan90", null); check("tg270", null); check("cot0", null); check("ctg180", null);
        draft("Найди sin60", "0.86602540378444"); draft("Result: cos14", "0.970295726276");
        draft("sin60 = 0.866", null); draft("http://sin60", null); check("sinh60", null);
        System.out.println("PASS: operators, precedence, decimal precision, invalid input and resource bounds");
    }
}
'''
with tempfile.TemporaryDirectory(prefix="ghost-calculator-") as directory:
    harness = Path(directory) / "CalculatorCheck.java"
    harness.write_text(test, encoding="utf-8")
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, str(source), str(harness)], check=True)
    subprocess.run(["java", "-cp", directory, "CalculatorCheck"], check=True)
