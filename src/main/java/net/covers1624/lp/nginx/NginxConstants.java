package net.covers1624.lp.nginx;

import java.util.List;

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
}
