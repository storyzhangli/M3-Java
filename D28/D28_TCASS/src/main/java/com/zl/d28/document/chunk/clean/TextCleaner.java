package com.zl.d28.document.chunk.clean;

import com.zl.d28.document.chunk.model.BlockType;
import com.zl.d28.document.chunk.model.CleanedDocument;
import com.zl.d28.document.chunk.model.CleanedPage;
import com.zl.d28.document.chunk.model.TextBlock;
import com.zl.d28.document.model.DocumentPageContent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本清洗/规范化组件（独立、可测试）。
 *
 * <p>基于已有的 PDF 逐页提取结果，按页清洗并保留页面定位，不重写解析。
 * 清洗规则：统一换行符、归一异常/连续水平空白、识别空行、折叠重复换行，
 * 同时保留标题/段落/列表结构与有效语义。</p>
 *
 * <p>四条禁令的落实：不删除所有换行（保留结构换行）、不把所有空白无差别
 * 压成一个空格（换行与行内空格区别处理）、不破坏标题/段落/列表关系、
 * 不删除有意义标点/数字/单位/术语。</p>
 */
@Component
public class TextCleaner {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+\\S.*");
    private static final Pattern SPECIAL_HEADING =
            Pattern.compile("^(Chapter|CHAPTER|Appendix|APPENDIX|Part|PART)\\b.*");
    private static final Pattern NUMBERED_SECTION =
            Pattern.compile("^\\d+(\\.\\d+)*\\.?\\s+\\S.*");
    private static final Pattern BULLET_LIST = Pattern.compile("^[•·‣*\\-]\\s+.*");
    private static final Pattern NUMBERED_LIST = Pattern.compile("^\\d+[.)]\\s+.*");
    private static final Pattern LETTER_LIST = Pattern.compile("^[a-zA-Z][.)]\\s+.*");
    private static final Pattern TERMINAL_PUNCT = Pattern.compile(".*[.!?。！？]$");

    /**
     * 清洗页面列表，返回规范全文、结构块与逐页摘要。
     */
    public CleanedDocument clean(List<DocumentPageContent> pages) {
        CleanedDocument result = new CleanedDocument();
        if (pages == null || pages.isEmpty()) {
            result.setEmpty(true);
            return result;
        }

        List<TextBlock> blocks = new ArrayList<>();
        List<CleanedPage> pageSummaries = new ArrayList<>();
        int originalTotal = 0;
        int paragraphIndex = 0;

        for (DocumentPageContent page : pages) {
            String raw = page.getContent() == null ? "" : page.getContent();
            originalTotal += raw.length();

            List<String> lines = normalizeToLines(raw);
            String pageText = String.join("\n", lines);
            boolean pageBlank = !hasLetterOrDigit(pageText);
            pageSummaries.add(new CleanedPage(
                    page.getPageNumber(), raw.length(), pageText.length(), pageBlank));

            // 每个非空行成为一个结构块；不自动合并相邻行，避免把不同条目/段落错误拼接。
            // 短块的合并交给结构切分器按长度处理，行为可预测且不破坏来源定位。
            for (String line : lines) {
                if (line.isEmpty()) {
                    continue;
                }
                BlockType type = classify(line);
                blocks.add(new TextBlock(paragraphIndex++, page.getPageNumber(), type, line));
            }
        }

        // 拼接规范全文并回填全局偏移。
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            TextBlock block = blocks.get(i);
            block.setGlobalStart(joined.length());
            joined.append(block.getText());
            block.setGlobalEnd(joined.length());
            if (i < blocks.size() - 1) {
                joined.append('\n');
            }
        }

        result.setJoinedText(joined.toString());
        result.setBlocks(blocks);
        result.setPages(pageSummaries);
        result.setOriginalLength(originalTotal);
        result.setCleanedLength(joined.length());
        result.setEmpty(blocks.isEmpty());
        return result;
    }

    /**
     * 统一换行并逐行归一水平空白，折叠连续空行（至多保留一个空行分隔）。
     */
    private List<String> normalizeToLines(String raw) {
        String normalized = raw.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\u2028", "\n")
                .replace("\u2029", "\n");
        String[] rawLines = normalized.split("\n", -1);

        List<String> lines = new ArrayList<>();
        boolean lastBlank = false;
        for (String rawLine : rawLines) {
            String line = normalizeHorizontalWhitespace(rawLine);
            if (line.isEmpty()) {
                if (!lastBlank && !lines.isEmpty()) {
                    lines.add("");
                }
                lastBlank = true;
            } else {
                lines.add(line);
                lastBlank = false;
            }
        }
        // 去掉首尾空行
        while (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * 行内水平空白（空格/Tab/NBSP/全角空格等）连续序列归一为单个普通空格，
     * 并去首尾空白；不触碰其他字符。
     */
    private String normalizeHorizontalWhitespace(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean pendingSpace = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                pendingSpace = sb.length() > 0;
            } else {
                if (pendingSpace) {
                    sb.append(' ');
                    pendingSpace = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private BlockType classify(String line) {
        if (MARKDOWN_HEADING.matcher(line).matches()
                || SPECIAL_HEADING.matcher(line).matches()) {
            return BlockType.HEADING;
        }
        if (BULLET_LIST.matcher(line).matches()
                || NUMBERED_LIST.matcher(line).matches()
                || LETTER_LIST.matcher(line).matches()) {
            return BlockType.LIST_ITEM;
        }
        if (NUMBERED_SECTION.matcher(line).matches() && line.length() <= 60
                && !TERMINAL_PUNCT.matcher(line).matches()) {
            return BlockType.HEADING;
        }
        if (isAllCapsShort(line)) {
            return BlockType.HEADING;
        }
        return BlockType.PARAGRAPH;
    }

    private boolean isAllCapsShort(String line) {
        boolean hasLetter = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return hasLetter && line.length() <= 50 && !TERMINAL_PUNCT.matcher(line).matches();
    }

    private boolean hasLetterOrDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
