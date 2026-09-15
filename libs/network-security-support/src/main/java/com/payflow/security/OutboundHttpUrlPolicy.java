package com.payflow.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Validates an operator-supplied outbound URL against the PayFlow SSRF boundary.
 *
 * <p>Production accepts HTTPS targets only and rejects every resolved non-public address. Local
 * demos may explicitly enable the unsafe escape hatch; callers should never enable it in a shared
 * or internet-facing environment. The URL is resolved on every check so delivery can re-check a
 * hostname that was safe when configured.
 */
public final class OutboundHttpUrlPolicy {

    private OutboundHttpUrlPolicy() {}

    public static URI requireAllowed(String rawUrl, boolean allowUnsafeLocalTargets) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException invalid) {
            throw rejected(invalid);
        }

        String scheme = uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean allowedScheme = "https".equals(scheme)
                || (allowUnsafeLocalTargets && "http".equals(scheme));
        if (!allowedScheme
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65_535) {
            throw rejected(null);
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) {
                throw rejected(null);
            }
            if (!allowUnsafeLocalTargets) {
                for (InetAddress address : addresses) {
                    if (!isPublic(address)) {
                        throw rejected(null);
                    }
                }
            }
        } catch (UnknownHostException unresolved) {
            throw rejected(unresolved);
        }
        return uri;
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? isPublicIpv4(bytes) : isPublicIpv6(bytes);
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192 && (second == 168 || (second == 0 && (third == 0 || third == 2)))) {
            return false;
        }
        if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) {
            return false;
        }
        return !(first == 203 && second == 0 && third == 113);
    }

    private static boolean isPublicIpv6(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        boolean uniqueLocal = (first & 0xfe) == 0xfc;
        boolean documentation = first == 0x20
                && second == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0d
                && Byte.toUnsignedInt(bytes[3]) == 0xb8;
        return !uniqueLocal && !documentation;
    }

    private static IllegalArgumentException rejected(Exception cause) {
        return new IllegalArgumentException(
                "outbound URL is not an allowed public HTTPS target", cause);
    }
}
