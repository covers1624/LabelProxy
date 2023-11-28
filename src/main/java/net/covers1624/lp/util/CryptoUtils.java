package net.covers1624.lp.util;

import java.io.PrintWriter;
import java.util.Base64;

/**
 * Created by covers1624 on 27/11/23.
 */
public class CryptoUtils {

    public static void writePem(PrintWriter writer, byte[] pemData, String type) {
        writer.println("-----BEGIN " + type + "-----");
        String str = Base64.getEncoder().encodeToString(pemData);
        for (int i = 0; i < str.length(); i += 64) {
            writer.println(str.substring(i, Math.min(i + 64, str.length())));
        }
        writer.println("-----END " + type + "-----");
    }
}
