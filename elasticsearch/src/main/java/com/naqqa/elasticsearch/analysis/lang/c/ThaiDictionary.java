package com.naqqa.elasticsearch.analysis.lang.c;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ThaiDictionary {

    private ThaiDictionary() {
    }

    private static final String WORDS_DATA = ""
        + "ฉัน ผม คุณ เขา มัน เรา พวกเขา ท่าน ดิฉัน "
        + "กิน ดื่ม ไป มา ทำ พูด ดู ฟัง นอน รัก ชอบ อยาก ต้อง ได้ เป็น อยู่ มี คือ "
        + "เห็น รู้ เข้าใจ อ่าน เขียน เรียน สอน ซื้อ ขาย ให้ เอา วิ่ง เดิน นั่ง ยืน "
        + "บ้าน คน น้ำ ข้าว รถ หนังสือ โรงเรียน ประเทศ เมือง วัน เวลา เงิน งาน อาหาร "
        + "ครู นักเรียน แม่ พ่อ ลูก เพื่อน ประเทศไทย กรุงเทพ ปี เดือน สัปดาห์ ชั่วโมง นาที "
        + "ต้นไม้ ดอกไม้ สุนัข แมว รองเท้า เสื้อ กางเกง โต๊ะ เก้าอี้ ประตู หน้าต่าง ถนน "
        + "อะไร ทำไม ที่ไหน เมื่อไหร่ อย่างไร ใคร กี่ ไหม ครับ ค่ะ นะ แล้ว ก็ และ หรือ "
        + "แต่ เพราะ ถ้า เมื่อ นี้ นั้น ที่ ของ ใน บน หน้า หลัง "
        + "หนึ่ง สอง สาม สี่ ห้า หก เจ็ด แปด เก้า สิบ "
        + "สวัสดี ขอบคุณ ขอโทษ สบายดี ยินดี ชื่อ โลก ไทย ภาษา คำ ประโยค "
        + "ใหญ่ เล็ก ดี สวย น่ารัก เร็ว ช้า ใหม่ เก่า ร้อน เย็น หนาว มาก น้อย "
        + "ทุก บาง อีก อื่น เอง กัน จะ กำลัง เคย";

    private static final Set<String> WORDS = build();
    private static final int MAX_WORD_LENGTH = computeMaxLength();

    private static Set<String> build() {
        return new HashSet<>(List.of(WORDS_DATA.split(" ")));
    }

    private static int computeMaxLength() {
        int max = 1;
        for (String w : WORDS) {
            max = Math.max(max, w.length());
        }
        return max;
    }

    public static boolean contains(String word) {
        return WORDS.contains(word);
    }

    public static int maxWordLength() {
        return MAX_WORD_LENGTH;
    }

    public static int size() {
        return WORDS.size();
    }
}
