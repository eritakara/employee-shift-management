package service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExportPerformanceTest {
  private static final int ROW_COUNT = 50_000;

  public static void main(String[] args) {
    List<Map<String, Object>> rows = new ArrayList<>(ROW_COUNT);
    for (int i = 0; i < ROW_COUNT; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("work_date", "2026-06-01");
      row.put("employee_number", "EM" + i);
      row.put("name", "従業員 " + i);
      row.put("branch", "那覇支店");
      row.put("department", "営業部");
      row.put("status", "COMPLETE");
      rows.add(row);
    }

    ExportService exports = new ExportService();
    long csvStarted = System.nanoTime();
    String csv = exports.csv(rows);
    long csvMillis = (System.nanoTime() - csvStarted) / 1_000_000;
    long excelStarted = System.nanoTime();
    String excel = exports.excelHtml(rows);
    long excelMillis = (System.nanoTime() - excelStarted) / 1_000_000;

    if (!csv.contains("EM49999") || !excel.contains("EM49999")) {
      throw new AssertionError("Export output is incomplete");
    }
    System.out.println("ExportPerformanceTest: rows=" + ROW_COUNT
        + ", csvMs=" + csvMillis + ", excelMs=" + excelMillis);
  }
}
