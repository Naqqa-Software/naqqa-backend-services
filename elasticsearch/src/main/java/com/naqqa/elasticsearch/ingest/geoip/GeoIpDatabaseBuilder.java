package com.naqqa.elasticsearch.ingest.geoip;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GeoIpDatabaseBuilder {

    private static final byte[] MAGIC = {'G', 'E', 'O', '1'};

    private record Entry(int network, int prefixLength, GeoIpRecord record) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public GeoIpDatabaseBuilder addCidr(String cidr, GeoIpRecord record) {
        String[] parts = cidr.split("/");
        int network = ipToInt(parts[0]);
        int prefixLength = parts.length > 1 ? Integer.parseInt(parts[1]) : 32;
        entries.add(new Entry(network, prefixLength, record));
        return this;
    }

    public static int ipToInt(String ip) {
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 4) {
                throw new IllegalArgumentException("only IPv4 is supported by this GeoIP database format: " + ip);
            }
            int result = 0;
            for (byte b : bytes) {
                result = (result << 8) | (b & 0xFF);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid IP address: " + ip, e);
        }
    }

    public void write(OutputStream out) throws IOException {
        Map<String, Integer> stringTable = new LinkedHashMap<>();
        List<String> strings = new ArrayList<>();
        for (Entry e : entries) {
            internAll(e.record(), stringTable, strings);
        }

        DataOutputStream dos = new DataOutputStream(out);
        dos.write(MAGIC);
        dos.writeInt(strings.size());
        for (String s : strings) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            dos.writeShort(bytes.length);
            dos.write(bytes);
        }
        dos.writeInt(entries.size());
        for (Entry e : entries) {
            GeoIpRecord r = e.record();
            dos.writeInt(e.network());
            dos.writeByte(e.prefixLength());
            dos.writeInt(intern(r.continentName(), stringTable));
            dos.writeInt(intern(r.countryIsoCode(), stringTable));
            dos.writeInt(intern(r.countryName(), stringTable));
            dos.writeInt(intern(r.regionName(), stringTable));
            dos.writeInt(intern(r.cityName(), stringTable));
            dos.writeInt(intern(r.timezone(), stringTable));
            dos.writeInt(intern(r.postalCode(), stringTable));
            dos.writeDouble(r.latitude() == null ? Double.NaN : r.latitude());
            dos.writeDouble(r.longitude() == null ? Double.NaN : r.longitude());
        }
        dos.flush();
    }

    private static void internAll(GeoIpRecord r, Map<String, Integer> stringTable, List<String> strings) {
        intern(r.continentName(), stringTable, strings);
        intern(r.countryIsoCode(), stringTable, strings);
        intern(r.countryName(), stringTable, strings);
        intern(r.regionName(), stringTable, strings);
        intern(r.cityName(), stringTable, strings);
        intern(r.timezone(), stringTable, strings);
        intern(r.postalCode(), stringTable, strings);
    }

    private static void intern(String s, Map<String, Integer> stringTable, List<String> strings) {
        if (s == null || stringTable.containsKey(s)) {
            return;
        }
        stringTable.put(s, strings.size());
        strings.add(s);
    }

    private static int intern(String s, Map<String, Integer> stringTable) {
        if (s == null) {
            return -1;
        }
        return stringTable.get(s);
    }
}
