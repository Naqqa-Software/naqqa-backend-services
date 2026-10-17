package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;

public record Cidr(byte[] network, int prefixLength) {
    public Cidr {
        network = network.clone();
        if ((network.length != 4 && network.length != 16) || prefixLength < 0 || prefixLength > network.length * 8) {
            throw new SsoException("cidr.invalid");
        }
    }

    @Override
    public byte[] network() {
        return network.clone();
    }

    public static Cidr parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 49) {
            throw new SsoException("cidr.invalid");
        }
        int slash = text.indexOf('/');
        String addrText = slash < 0 ? text : text.substring(0, slash);
        byte[] addr;
        try {
            addr = IpAddresses.parse(addrText);
        } catch (SsoException e) {
            throw new SsoException("cidr.invalid");
        }
        int max = addr.length * 8;
        int prefix = max;
        if (slash >= 0) {
            String p = text.substring(slash + 1);
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
                throw new SsoException("cidr.invalid");
            }
            prefix = 0;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') {
                    throw new SsoException("cidr.invalid");
                }
                prefix = prefix * 10 + (c - '0');
            }
            if (prefix > max) {
                throw new SsoException("cidr.invalid");
            }
        }
        for (int bit = prefix; bit < max; bit++) {
            if ((addr[bit >> 3] & (0x80 >> (bit & 7))) != 0) {
                throw new SsoException("cidr.host_bits");
            }
        }
        return new Cidr(addr, prefix);
    }

    public boolean contains(String ip) {
        byte[] addr;
        try {
            addr = IpAddresses.parse(ip);
        } catch (SsoException e) {
            return false;
        }
        return contains(addr);
    }

    public boolean contains(byte[] addr) {
        byte[] a = network.length == 4 ? IpAddresses.unmapV4(addr) : addr;
        if (a.length != network.length) {
            return false;
        }
        int full = prefixLength >> 3;
        for (int i = 0; i < full; i++) {
            if (a[i] != network[i]) {
                return false;
            }
        }
        int rem = prefixLength & 7;
        if (rem == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rem)) & 0xFF;
        return (a[full] & mask) == (network[full] & mask);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cidr && ((Cidr) o).prefixLength == prefixLength && java.util.Arrays.equals(((Cidr) o).network, network);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Arrays.hashCode(network) + prefixLength;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (network.length == 4) {
            for (int i = 0; i < 4; i++) {
                if (i > 0) {
                    sb.append('.');
                }
                sb.append(network[i] & 0xFF);
            }
        } else {
            for (int i = 0; i < 8; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(Integer.toHexString(((network[2 * i] & 0xFF) << 8) | (network[2 * i + 1] & 0xFF)));
            }
        }
        return sb.append('/').append(prefixLength).toString();
    }
}
