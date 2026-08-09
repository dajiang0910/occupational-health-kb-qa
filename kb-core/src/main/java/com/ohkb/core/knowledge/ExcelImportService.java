package com.ohkb.core.knowledge;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel FAQ 批量导入服务——PRD User Story #5。
 * <p>
 * 解析 Excel 文件（.xlsx），支持以下列格式：
 * <ul>
 *   <li>列 A: 问题（必填）</li>
 *   <li>列 B: 回答（必填）</li>
 *   <li>列 C: 分类（可选，默认 general）</li>
 *   <li>列 D: 标签（可选，逗号分隔）</li>
 * </ul>
 * </p>
 */
@Service
public class ExcelImportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelImportService.class);

    /**
     * 解析 Excel 文件，返回 Q&A 对列表。
     *
     * @param file 上传的 Excel 文件
     * @return 解析出的 FAQ 条目列表
     */
    public List<FaqEntry> parseExcel(MultipartFile file) throws IOException {
        List<FaqEntry> entries = new ArrayList<>();
        int totalRows = 0;
        int skippedRows = 0;

        try (InputStream is = file.getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IOException("Excel 文件中没有找到工作表");
            }

            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;

                // 跳过表头行（第一行包含"问题"/"回答"等标题）
                if (rowIndex == 0) {
                    String firstCell = getCellString(row, 0);
                    if (firstCell != null && (firstCell.contains("问题") || firstCell.contains("回答")
                            || firstCell.contains("question") || firstCell.contains("answer"))) {
                        continue; // 跳过表头
                    }
                }

                totalRows++;

                String question = getCellString(row, 0);
                String answer = getCellString(row, 1);

                // 必填字段校验
                if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
                    skippedRows++;
                    log.debug("[EXCEL] Skipped row {}: empty question or answer", rowIndex + 1);
                    continue;
                }

                String category = getCellString(row, 2);
                if (category == null || category.isBlank()) {
                    category = "general";
                }

                String tagsStr = getCellString(row, 3);
                List<String> tags = tagsStr != null && !tagsStr.isBlank()
                        ? List.of(tagsStr.split("[,，]"))
                        : List.of();

                entries.add(new FaqEntry(question.trim(), answer.trim(), category.trim(), tags));
            }
        }

        log.info("[EXCEL] Parsed {} entries from {} ({} skipped, {} total rows)",
                entries.size(), file.getOriginalFilename(), skippedRows, totalRows);

        return entries;
    }

    /**
     * 从 Excel 单元格读取字符串值。
     */
    private String getCellString(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                // 避免数字格式化为 "1.0" 这种
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> null;
        };
    }

    /**
     * FAQ 条目。
     */
    public record FaqEntry(String question, String answer, String category, List<String> tags) {}
}
