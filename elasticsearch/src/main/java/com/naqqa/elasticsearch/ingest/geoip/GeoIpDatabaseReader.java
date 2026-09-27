package com.naqqa.elasticsearch.ingest.geoip;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GeoIpDatabaseReader {

    private record Entry(int network, int prefixLength, int continentIdx, int countryIsoIdx, int countryNameIdx,
                          int regionNameIdx, int cityNameIdx, int timezoneIdx, int postalCodeIdx,
                          double latitude, double longitude) {
    }

    private final String[] strings;
    private final Entry[] entries;

    private GeoIpDatabaseReader(String[] strings, Entry[] entries) {
        this.strings = strings;
        this.entries = entries;
    }

    public static GeoIpDatabaseReader load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        }
    }

    public static GeoIpDatabaseReader load(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        byte[] magic = new byte[4];
        dis.readFully(magic);
        if (magic[0] != 'G' || magic[1] != 'E' || magic[2] != 'O' || magic[3] != '1') {
            throw new IOException("invalid GeoIP database magic header");
        }
        int stringCount = dis.readInt();
        String[] strings = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int len = dis.readUnsignedShort();
            byte[] bytes = new byte[len];
            dis.readFully(bytes);
            strings[i] = new String(bytes, StandardCharsets.UTF_8);
        }
        int entryCount = dis.readInt();
        Entry[] entries = new Entry[entryCount];
        for (int i = 0; i < entryCount; i++) {
            int network = dis.readInt();
            int prefixLength = dis.readUnsignedByte();
            int continentIdx = dis.readInt();
            int countryIsoIdx = dis.readInt();
            int countryNameIdx = dis.readInt();
            int regionNameIdx = dis.readInt();
            int cityNameIdx = dis.readInt();
            int timezoneIdx = dis.readInt();
            int postalCodeIdx = dis.readInt();
            double lat = dis.readDouble();
            double lon = dis.readDouble();
            entries[i] = new Entry(network, prefixLength, continentIdx, countryIsoIdx, countryNameIdx, regionNameIdx,
                cityNameIdx, timezoneIdx, postalCodeIdx, lat, lon);
        }
        return new GeoIpDatabaseReader(strings, entries);
    }

    public GeoIpRecord lookup(String ip) {
        int target;
        try {
            target = GeoIpDatabaseBuilder.ipToInt(ip);
        } catch (Exception e) {
            return null;
        }
        Entry best = null;
        for (Entry e : entries) {
            int maskBits = e.prefixLength();
            int mask = maskBits == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - maskBits));
            if ((target & mask) == (e.network() & mask)) {
                if (best == null || e.prefixLength() > best.prefixLength()) {
                    best = e;
                }
            }
        }
        if (best == null) {
            return null;
        }
        return new GeoIpRecord(
            str(best.continentIdx()), str(best.countryIsoIdx()), str(best.countryNameIdx()),
            str(best.regionNameIdx()), str(best.cityNameIdx()), str(best.timezoneIdx()), str(best.postalCodeIdx()),
            Double.isNaN(best.latitude()) ? null : best.latitude(),
            Double.isNaN(best.longitude()) ? null : best.longitude()
        );
    }

    private String str(int idx) {
        return idx < 0 ? null : strings[idx];
    }
}
