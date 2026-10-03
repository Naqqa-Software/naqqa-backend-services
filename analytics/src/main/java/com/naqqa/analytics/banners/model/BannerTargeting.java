package com.naqqa.analytics.banners.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BannerTargeting {

    public enum Visitor {
        ANY,
        NEW,
        RETURNING;

        @JsonCreator
        public static Visitor of(String value) {
            if (value == null || value.isBlank()) {
                return ANY;
            }
            for (Visitor v : values()) {
                if (v.name().equalsIgnoreCase(value.trim())) {
                    return v;
                }
            }
            return ANY;
        }

        @JsonValue
        public String json() {
            return name().toLowerCase();
        }
    }

    public enum LoggedIn {
        ANY,
        YES,
        NO;

        @JsonCreator
        public static LoggedIn of(String value) {
            if (value == null || value.isBlank()) {
                return ANY;
            }
            for (LoggedIn v : values()) {
                if (v.name().equalsIgnoreCase(value.trim())) {
                    return v;
                }
            }
            return ANY;
        }

        @JsonValue
        public String json() {
            return name().toLowerCase();
        }
    }

    private List<String> slots = new ArrayList<>();
    private List<String> pageTypes = new ArrayList<>();
    private List<String> categoryIds = new ArrayList<>();
    private List<String> companyIds = new ArrayList<>();
    private List<String> langs = new ArrayList<>();
    private List<String> devices = new ArrayList<>();
    private List<String> cities = new ArrayList<>();
    private List<String> keywords = new ArrayList<>();
    private Visitor visitor = Visitor.ANY;
    private LoggedIn loggedIn = LoggedIn.ANY;
    private List<Integer> days = new ArrayList<>();
    private List<Integer> hours = new ArrayList<>();
}
