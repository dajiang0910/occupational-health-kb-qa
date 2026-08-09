package com.ohkb.infra.document;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档解析器 — 结构感知分块。
 * <p>
 * 使用 Apache Tika 解析多种文档格式，按标题层级 + 段落语义进行分块。
 * 表格区域检测：同时输出 Markdown（LLM 友好）+ JSON（检索过滤）。
 */
@Component
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);

    // 匹配 Markdown/Word 风格标题
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#{1,6}\\s|第[一二三四五六七八九十]+[章节条款]).+", Pattern.MULTILINE);

    // 匹配表格标记
    private static final Pattern TABLE_START = Pattern.compile("^\\|[-|\\s]+\\|$", Pattern.MULTILINE);

    private final int chunkSize;
    private final int chunkOverlap;

    public DocumentParser(
            @Value("${app.document.chunk-size:512}") int chunkSize,
            @Value("${app.document.chunk-overlap:50}") int chunkOverlap
    ) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * 解析文档文件，返回结构化的 TextSegment 列表。
     *
     * @param filePath 文件路径
     * @return 分块后的 TextSegment 列表（含 metadata：heading、chunkIndex、isTable）
     */
    public List<TextSegment> parse(Path filePath) throws IOException {
        String filename = filePath.getFileName().toString();
        String fileType = getFileType(filename);
        log.info("[DOC] Parsing {} (type={}, size={})", filename, fileType, Files.size(filePath));

        // 使用 LangChain4j 的 Apache Tika 加载器
        Document document = FileSystemDocumentLoader.loadDocument(
                filePath, new ApacheTikaDocumentParser());

        String text = document.text();
        if (text == null || text.isBlank()) {
            log.warn("[DOC] Empty content for {}", filename);
            return List.of();
        }

        // 结构感知分块
        List<TextSegment> chunks = splitStructureAware(text, filename);

        log.info("[DOC] Parsed {} into {} chunks", filename, chunks.size());
        return chunks;
    }

    /**
     * 结构感知分块：按标题层级拆分，表格作为整体保留。
     */
    List<TextSegment> splitStructureAware(String text, String source) {
        // Step 1: 按标题拆分段落组
        List<Section> sections = splitByHeadings(text);

        // Step 2: 对每个段落组，检测表格并单独处理
        List<TextSegment> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (Section section : sections) {
            String sectionText = section.text();
            String heading = section.heading();

            // 检测表格
            if (containsTable(sectionText)) {
                // 表格作为独立 chunk（不拆分），附加 Markdown + JSON metadata
                var metadata = new dev.langchain4j.data.document.Metadata();
                metadata.put("heading", heading != null ? heading : "");
                metadata.put("source", source);
                metadata.put("chunkIndex", String.valueOf(chunkIndex++));
                metadata.put("isTable", "true");
                metadata.put("tableJson", extractTableAsJson(sectionText));
                chunks.add(TextSegment.from(sectionText, metadata));
            } else if (sectionText.length() <= chunkSize) {
                // 短段落直接作为一个 chunk
                var metadata = new dev.langchain4j.data.document.Metadata();
                metadata.put("heading", heading != null ? heading : "");
                metadata.put("source", source);
                metadata.put("chunkIndex", String.valueOf(chunkIndex++));
                metadata.put("isTable", "false");
                chunks.add(TextSegment.from(sectionText, metadata));
            } else {
                // 长段落按 Semantically 分块
                DocumentSplitter splitter = new DocumentSplitter(chunkSize, chunkOverlap);
                List<TextSegment> subChunks = splitter.split(
                        TextSegment.from(sectionText));
                for (TextSegment sub : subChunks) {
                    var metadata = new dev.langchain4j.data.document.Metadata();
                    metadata.put("heading", heading != null ? heading : "");
                    metadata.put("source", source);
                    metadata.put("chunkIndex", String.valueOf(chunkIndex++));
                    metadata.put("isTable", "false");
                    chunks.add(TextSegment.from(sub.text(), metadata));
                }
            }
        }

        return chunks;
    }

    /**
     * 按标题层级拆分文本为段落组。
     */
    private List<Section> splitByHeadings(String text) {
        List<Section> sections = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder currentHeading = new StringBuilder();
        StringBuilder currentBody = new StringBuilder();

        for (String line : lines) {
            if (HEADING_PATTERN.matcher(line.trim()).matches()) {
                // 遇到标题 → 保存上一段
                if (!currentBody.isEmpty()) {
                    sections.add(new Section(
                            currentHeading.isEmpty() ? null : currentHeading.toString(),
                            currentBody.toString().trim()));
                }
                currentHeading = new StringBuilder(line.trim());
                currentBody = new StringBuilder();
            } else {
                currentBody.append(line).append("\n");
            }
        }
        // 最后一段
        if (!currentBody.isEmpty()) {
            sections.add(new Section(
                    currentHeading.isEmpty() ? null : currentHeading.toString(),
                    currentBody.toString().trim()));
        }
        return sections;
    }

    /**
     * 检测文本是否包含表格。
     */
    private boolean containsTable(String text) {
        return text.contains("|") && TABLE_START.matcher(text).find();
    }

    /**
     * 提取表格的 JSON 表示（简化版，Phase 1 仅保留 Markdown）。
     */
    private String extractTableAsJson(String text) {
        // Phase 1：保留完整的 Markdown 表格作为 JSON 字符串
        // Phase 2：解析为结构化 JSON 数组
        return "{\"markdown\": " +
                text.lines()
                        .filter(l -> l.contains("|"))
                        .collect(Collectors.joining("\\n", "\"", "\""))
                + "}";
    }

    private String getFileType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".md")) return "md";
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".xlsx")) return "xlsx";
        return "unknown";
    }

    /**
     * 段落组：标题 + 正文。
     */
    private record Section(String heading, String text) {}
}
