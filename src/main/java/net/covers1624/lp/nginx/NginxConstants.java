package net.covers1624.lp.nginx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by covers1624 on 4/11/23.
 */
public class NginxConstants {

    public static final List<String> SSL_PROTOCOLS = List.of(
            "TLSv1.2",
            "TLSv1.3"
    );

    // List supported by dash.cloudflare.com as of 4/11/23
    public static final List<String> SSL_CIPHERS = List.of(
            // TLS 1.3
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_AES_128_GCM_SHA256",
            // TLS 1.2
            "ECDHE-ECDSA-AES128-GCM-SHA256",
            "ECDHE-ECDSA-CHACHA20-POLY1305-OLD",
            "ECDHE-ECDSA-CHACHA20-POLY1305",
            "ECDHE-ECDSA-AES128-SHA",               // Weak
            "ECDHE-ECDSA-AES256-GCM-SHA384",
            "ECDHE-ECDSA-AES256-SHA384",            // Weak
            "ECDHE-ECDSA-AES128-SHA256",            // Weak
            "ECDHE-ECDSA-AES256-SHA384",            // Weak
            "ECDHE-RSA-AES128-GCM-SHA256",
            "ECDHE-RSA-CHACHA20-POLY1305-OLD",
            "ECDHE-RSA-CHACHA20-POLY1305",
            "ECDHE-RSA-AES128-SHA",                 // Weak
            "AES128-GCM-SHA256",                    // Weak
            "AES128-SHA",                           // Weak
            "ECDHE-RSA-AES256-GCM-SHA384",
            "ECDHE-RSA-AES256-SHA",                 // Weak
            "AES256-GCM-SHA384",                    // Weak
            "AES256-SHA",                           // Weak
            "ECDHE-RSA-AES128-SHA256",
            "AES128-SHA256",
            "ECDHE-RSA-AES256-SHA384",
            "AES256-SHA256"
    );

    // Probably wayy more many mime types than is required. These were yoinked from the Arch package.
    // The nginx alpine container has only the common ones.
    public static final Map<String, String[]> MIME_TYPES = loadMimeTypes();

    private static Map<String, String[]> loadMimeTypes() {
        Map<String, String[]> types = new LinkedHashMap<>();
        try (InputStream is = NginxConstants.class.getResourceAsStream("/mime.types")) {
            if (is == null) throw new RuntimeException("mime.types resource is missing.");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] splits = line.split("\t");
                types.put(splits[0], Arrays.copyOfRange(splits, 1, splits.length));
            }
        } catch (IOException ex) {
            throw new RuntimeException("Unable to parse mime types.");
        }
        return types;
    }
}
