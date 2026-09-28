package com.naqqa.elasticsearch.bench.amazon;

import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class AmazonCsvReaderTest {

    private Path writeCsv(String content) throws Exception {
        Path p = Files.createTempFile("amazon-csv-test-", ".csv");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    public void parsesSimpleRows() throws Exception {
        Path p = writeCsv("asin,title,imgUrl,productURL,stars,reviews,price,listPrice,category_id,isBestSeller,boughtInLastMonth\n"
            + "B001,Simple Title,http://img,http://url,4.5,10,19.99,0.0,104,False,50\n");
        try (AmazonCsvReader reader = new AmazonCsvReader(p)) {
            List<String> header = reader.readRow();
            assertEquals(11, header.size());
            assertEquals("asin", header.get(0));
            List<String> row = reader.readRow();
            assertEquals(11, row.size());
            assertEquals("B001", row.get(0));
            assertEquals("Simple Title", row.get(1));
            assertEquals("19.99", row.get(6));
            assertNull(reader.readRow());
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void handlesQuotedFieldWithEmbeddedComma() throws Exception {
        Path p = writeCsv("B002,\"Sion Softside, Expandable Roller Luggage, Black\",img,url,4.5,0,139.99,0.0,104,False,2000\n");
        try (AmazonCsvReader reader = new AmazonCsvReader(p)) {
            List<String> row = reader.readRow();
            assertEquals(11, row.size());
            assertEquals("Sion Softside, Expandable Roller Luggage, Black", row.get(1));
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void handlesDoubledQuotesInsideQuotedField() throws Exception {
        Path p = writeCsv("B003,\"He said \"\"hello\"\" there\",img,url,4.0,1,9.99,0.0,1,True,0\n");
        try (AmazonCsvReader reader = new AmazonCsvReader(p)) {
            List<String> row = reader.readRow();
            assertEquals("He said \"hello\" there", row.get(1));
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void handlesCrlfLineEndings() throws Exception {
        Path p = writeCsv("B004,Title One,img,url,3.0,2,5.00,0.0,1,False,0\r\nB005,Title Two,img,url,3.5,3,6.00,0.0,2,True,10\r\n");
        try (AmazonCsvReader reader = new AmazonCsvReader(p)) {
            List<String> row1 = reader.readRow();
            assertEquals("B004", row1.get(0));
            assertEquals("Title One", row1.get(1));
            List<String> row2 = reader.readRow();
            assertEquals("B005", row2.get(0));
            assertNull(reader.readRow());
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void handlesEmbeddedNewlineInQuotedField() throws Exception {
        Path p = writeCsv("B006,\"Multi\nLine Title\",img,url,4.0,1,1.00,0.0,1,False,0\nB007,Next,img,url,4.0,1,1.00,0.0,1,False,0\n");
        try (AmazonCsvReader reader = new AmazonCsvReader(p)) {
            List<String> row1 = reader.readRow();
            assertEquals("Multi\nLine Title", row1.get(1));
            List<String> row2 = reader.readRow();
            assertEquals("B007", row2.get(0));
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void lastRowWithoutTrailingNewlineIsReturned() throws Exception {
        Path p = writeCsv("B008,No Trailing Newline,img,url,4.0,1,1.00,0.0,1,False,0");
        try (AmazonCsvReader reader = new AmazonCsvReader(p)) {
            List<String> row = reader.readRow();
            assertEquals("B008", row.get(0));
            assertEquals("No Trailing Newline", row.get(1));
            assertNull(reader.readRow());
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void malformedRowWithWrongColumnCountIsRejectedByParser() throws Exception {
        Path p = writeCsv("B009,Too Few Columns,img,url,4.0,1,1.00\n");
        try (AmazonCsvReader reader = new AmazonCsvReader(p)) {
            List<String> row = reader.readRow();
            assertTrue(row.size() != AmazonProductParser.EXPECTED_COLUMNS);
            assertNull(AmazonProductParser.parse(row));
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void parserAppliesDefaultsAndComputesDiscount() {
        List<String> row = List.of("B010", "Discounted Item", "img", "url", "", "", "80.00", "100.00", "104", "True", "");
        AmazonProduct product = AmazonProductParser.parse(row);
        assertEquals("B010", product.asin());
        assertEquals(0, product.reviews());
        assertEquals(0, product.boughtInLastMonth());
        assertTrue(product.bestSeller());
        assertNull(product.stars());
        Integer discount = AmazonDocBuilder.discountPercent(product);
        assertEquals(20, discount.intValue());
    }

    @Test
    public void parserRejectsBlankAsinOrUnparseablePrice() {
        List<String> blankAsin = List.of("", "T", "i", "u", "4.0", "1", "1.00", "0.0", "1", "False", "0");
        assertNull(AmazonProductParser.parse(blankAsin));
        List<String> badPrice = List.of("B011", "T", "i", "u", "4.0", "1", "not-a-number", "0.0", "1", "False", "0");
        assertNull(AmazonProductParser.parse(badPrice));
    }
}
