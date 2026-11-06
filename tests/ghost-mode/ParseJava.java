import java.io.File;
import java.util.ArrayList;
import javax.tools.*;
import com.sun.source.util.JavacTask;

/** Syntax-only check, deliberately not a substitute for the Android build. */
public class ParseJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            ArrayList<File> sources = new ArrayList<>();
            for (String path : args) sources.add(new File(path));
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics, null, null, files.getJavaFileObjectsFromFiles(sources));
            task.parse();
            for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(diagnostic.toString());
            }
        }
        System.out.println("Java syntax: " + args.length + " files passed.");
    }
}
