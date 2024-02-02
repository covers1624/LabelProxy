package net.covers1624.lp.nginx;

import net.covers1624.quack.io.IndentPrintWriter;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Created by covers1624 on 3/11/23.
 */
public abstract class NginxConfigGenerator {

    protected final StringWriter sw = new StringWriter();
    protected final IndentPrintWriter pw = new IndentPrintWriter(new PrintWriter(sw, true));

    protected void emitBlank() {
        pw.println();
    }

    protected void emit(String line) {
        pw.println(line + ";");
    }

    protected void emitBraced(String key, Runnable action) {
        pw.println(key + " {");
        pw.pushIndent();
        action.run();
        pw.popIndent();
        pw.println("}");
    }

    public static abstract class Simple extends NginxConfigGenerator {

        public abstract String generate();
    }
}
