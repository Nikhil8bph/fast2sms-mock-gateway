package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.exception.GatewayException;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DltExportReader {
    private static final Set<String> APPROVED = Set.of("approved", "active", "live", "registered");
    private static final Map<String, Set<String>> HEADERS = Map.of(
            "sender_id", Set.of("sender", "senderid", "smsheader", "header", "headername", "headerid", "headervalue", "headercode"),
            "template_id", Set.of("templateid", "contenttemplateid", "templatedltid", "dlttemplateid", "contentid"),
            "message_text", Set.of("messagetext", "content", "templatecontent", "templatetext", "contenttext", "message"),
            "template_type", Set.of("type", "templatetype", "category", "contenttype", "oldcontenttype"),
            "status", Set.of("status", "headerstatus", "templatestatus", "approvalstatus", "contentstatus"));

    static {
        ZipSecureFile.setMaxEntrySize(4_000_000);
        ZipSecureFile.setMaxFileCount(64);
    }

    public List<Map<String, Object>> read(byte[] contents, boolean templates) {
        if (contents.length == 0 || contents.length > 2_000_000) {
            throw GatewayException.badRequest(400, "DLT export must be between 1 byte and 2 MB");
        }
        try {
            List<List<String>> rows = contents.length >= 2 && contents[0] == 'P' && contents[1] == 'K'
                    ? excel(contents) : csv(contents);
            return resources(rows, templates);
        } catch (IOException | UncheckedIOException | IllegalArgumentException | POIXMLException exception) {
            throw GatewayException.badRequest(400, "Invalid DLT export. Supply a readable XLSX or CSV file.");
        }
    }

    private List<List<String>> excel(byte[] contents) throws IOException {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(contents))) {
            var format = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : workbook.getSheetAt(0)) {
                List<String> cells = new ArrayList<>();
                int width = Math.min(100, row.getLastCellNum());
                for (int index = 0; index < width; index++) {
                    cells.add(format.formatCellValue(row.getCell(index)));
                }
                rows.add(cells);
                enforceRowLimit(rows.size());
            }
            return rows;
        }
    }

    private List<List<String>> csv(byte[] contents) throws IOException {
        try (var reader = new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8);
                var parser = CSVFormat.DEFAULT.parse(reader)) {
            List<List<String>> rows = new ArrayList<>();
            for (var record : parser) {
                rows.add(record.toList());
                enforceRowLimit(rows.size());
            }
            return rows;
        }
    }

    private List<Map<String, Object>> resources(List<List<String>> rows, boolean templates) {
        Iterator<List<String>> iterator = rows.iterator();
        Map<String, Integer> columns = headerRow(iterator, templates);
        List<Map<String, Object>> resources = new ArrayList<>();
        while (iterator.hasNext()) {
            List<String> row = iterator.next();
            String status = value(row, columns.get("status")).trim().toLowerCase(Locale.ROOT);
            if (columns.containsKey("status") && !APPROVED.contains(status)) {
                continue;
            }
            String sender = value(row, columns.get("sender_id")).trim();
            if (sender.isBlank()) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sender_id", sender);
            if (templates) {
                String id = value(row, columns.get("template_id")).trim();
                String text = value(row, columns.get("message_text"));
                if (id.isBlank() || text.isBlank()) {
                    continue;
                }
                data.put("template_id", id);
                data.put("message_text", text);
                data.put("template_type", value(row, columns.get("template_type")).trim());
            }
            resources.add(data);
        }
        return resources;
    }

    private Map<String, Integer> headerRow(Iterator<List<String>> iterator, boolean templates) {
        while (iterator.hasNext()) {
            List<String> row = iterator.next();
            Map<String, Integer> columns = new HashMap<>();
            for (int index = 0; index < row.size(); index++) {
                String name = row.get(index).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                int column = index;
                HEADERS.forEach((field, aliases) -> {
                    if (aliases.contains(name)) {
                        columns.putIfAbsent(field, column);
                    }
                });
            }
            if (columns.containsKey("sender_id")
                    && (!templates || (columns.containsKey("template_id") && columns.containsKey("message_text")))) {
                return columns;
            }
        }
        throw GatewayException.badRequest(400, "DLT export headers were not recognized");
    }

    private void enforceRowLimit(int count) {
        if (count > 5000) {
            throw GatewayException.badRequest(400, "DLT export exceeds 5000 rows");
        }
    }

    private String value(List<String> row, Integer index) {
        return index == null || index >= row.size() ? "" : row.get(index);
    }
}
