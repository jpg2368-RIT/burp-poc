import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.heading.anchor.IdGenerator;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownRenderer extends AbstractVisitor {
    public static final String CODE_BLOCK_ATTR = "codeBlock";
    public static final String LINK_ATTR = "linkUrl";
    private Color linkColor = new Color(0, 90, 200);

    private static final Pattern ANCHOR_OPEN = Pattern.compile("(?i)^<(a|span)\\b[^>]*\\b(?:id|name)\\s*=\\s*\"([^\"]+)\"[^>]*>$");
    private static final Pattern ANCHOR_CLOSE = Pattern.compile("(?i)^</(a|span)>$");
    private static final Pattern ANCHOR_PAIR = Pattern.compile("(?i)<(a|span)\\b[^>]*\\b(?:id|name)\\s*=\\s*\"([^\"]+)\"[^>]*></(?:a|span)>");

    private final Map<String, Integer> anchorOffsets = new HashMap<>();
    private final Parser parser;
    private StyledDocument doc;
    private int baseFontSize = 12;
    private SimpleAttributeSet currentStyle = new SimpleAttributeSet();
    private int listDepth = 0;
    private final int[] orderedCounters = new int[8];
    private IdGenerator idGen;
    private String pendingAnchorClose;

    public MarkdownRenderer() {
        parser = Parser.builder()
                .extensions(List.of(TablesExtension.create(), AutolinkExtension.create()))
                .build();
    }

    public void setLinkColor(Color color) {
        if (color != null) linkColor = color;
    }

    public Map<String, Integer> anchorOffsets() {
        return new HashMap<>(anchorOffsets);
    }

    public void clearAnchors() {
        anchorOffsets.clear();
    }

    public void render(StyledDocument doc, String markdown) {
        if (markdown == null || markdown.isEmpty()) return;
        this.doc = doc;
        listDepth = 0;
        Arrays.fill(orderedCounters, 0);
        idGen = IdGenerator.builder().build();
        pendingAnchorClose = null;
        Node root = parser.parse(normalizeTables(markdown));
        root.accept(this);
    }

    // ---------------------------------------------------------------
    //  lenient table detection: insert a GFM delimiter row when the
    //  LLM omits it, so pipe blocks still parse as tables
    // ---------------------------------------------------------------

    private String normalizeTables(String markdown) {
        String[] lines = markdown.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
            }
            if (inFence || !isPipeLine(line) || i + 1 >= lines.length || !isPipeLine(lines[i + 1])) {
                out.add(line);
                i++;
                continue;
            }
            int runEnd = i + 1;
            while (runEnd < lines.length && isPipeLine(lines[runEnd])) runEnd++;
            if (lines[i].startsWith("    ") || lines[i].startsWith("\t")) {
                out.addAll(Arrays.asList(lines).subList(i, runEnd));
                i = runEnd;
                continue;
            }
            String header = lines[i];
            String second = lines[i + 1];
            if (!isDelimiterRow(second)) {
                out.add(header);
                out.add(delimiterFor(header));
                out.addAll(Arrays.asList(lines).subList(i + 1, runEnd));
            } else {
                out.addAll(Arrays.asList(lines).subList(i, runEnd));
            }
            i = runEnd;
        }
        return String.join("\n", out);
    }

    private boolean isPipeLine(String line) {
        String t = line.trim();
        return t.contains("|");
    }

    private boolean isDelimiterRow(String line) {
        String t = line.trim();
        if (!t.contains("|")) return false;
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        boolean anyDash = false;
        for (String cell : t.split("\\|")) {
            String c = cell.trim();
            if (c.isEmpty() || c.equals(":")) return false;
            boolean dash = false;
            for (int j = 0; j < c.length(); j++) {
                char ch = c.charAt(j);
                if (ch != '-' && ch != ':') return false;
                if (ch == '-') dash = true;
            }
            if (!dash) return false;
            anyDash = true;
        }
        return anyDash;
    }

    private int columnCount(String headerLine) {
        String t = headerLine.trim();
        String[] parts = t.split("\\|", -1);
        int cols = parts.length;
        if (t.startsWith("|")) cols--;
        if (t.endsWith("|")) cols--;
        return Math.max(1, cols);
    }

    private String delimiterFor(String headerLine) {
        StringBuilder indent = new StringBuilder();
        int i = 0;
        while (i < headerLine.length() && (headerLine.charAt(i) == ' ' || headerLine.charAt(i) == '\t')) {
            indent.append(headerLine.charAt(i));
            i++;
        }
        StringBuilder sb = new StringBuilder(indent.toString()).append("|");
        sb.repeat(" --- |", Math.max(0, columnCount(headerLine)));
        return sb.toString();
    }

    // ---------------------------------------------------------------
    //  inline nodes
    // ---------------------------------------------------------------

    @Override
    public void visit(Text text) {
        append(text.getLiteral());
    }

    @Override
    public void visit(Code code) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setFontFamily(currentStyle, Font.MONOSPACED);
        append(code.getLiteral());
        popStyle(saved);
    }

    @Override
    public void visit(Emphasis emphasis) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setItalic(currentStyle, true);
        visitChildren(emphasis);
        popStyle(saved);
    }

    @Override
    public void visit(StrongEmphasis strongEmphasis) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setBold(currentStyle, true);
        visitChildren(strongEmphasis);
        popStyle(saved);
    }

    @Override
    public void visit(Link link) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setForeground(currentStyle, linkColor);
        StyleConstants.setUnderline(currentStyle, true);
        currentStyle.addAttribute(LINK_ATTR, link.getDestination());
        visitChildren(link);
        popStyle(saved);
    }

    @Override
    public void visit(Image image) {
        if (image.getTitle() != null) append("[" + image.getTitle() + "]");
        else if (image.getDestination() != null) append("[image]");
    }

    @Override
    public void visit(SoftLineBreak softLineBreak) {
        append("\n");
    }

    @Override
    public void visit(HardLineBreak hardLineBreak) {
        append("\n");
    }

    @Override
    public void visit(HtmlInline htmlInline) {
        String literal = htmlInline.getLiteral();
        Matcher close = ANCHOR_CLOSE.matcher(literal);
        if (close.matches()) {
            if (pendingAnchorClose != null && pendingAnchorClose.equals(close.group(1).toLowerCase())) {
                pendingAnchorClose = null;
                return;
            }
            pendingAnchorClose = null;
            append(literal);
            return;
        }
        Matcher open = ANCHOR_OPEN.matcher(literal);
        if (open.matches()) {
            anchorOffsets.put(open.group(2), doc.getLength());
            pendingAnchorClose = open.group(1).toLowerCase();
            return;
        }
        pendingAnchorClose = null;
        append(literal);
    }

    // ---------------------------------------------------------------
    //  blocks
    // ---------------------------------------------------------------

    @Override
    public void visit(Paragraph paragraph) {
        visitChildren(paragraph);
        append("\n");
    }

    @Override
    public void visit(Heading heading) {
        int start = doc.getLength();
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setBold(currentStyle, true);
        StyleConstants.setFontSize(currentStyle, headingSize(heading.getLevel()));
        visitChildren(heading);
        popStyle(saved);
        String text = headingText(heading);
        if (idGen != null && !text.isEmpty()) {
            anchorOffsets.put(idGen.generateId(text), start);
        }
        append("\n\n");
    }

    @Override
    public void visit(BulletList bulletList) {
        listDepth++;
        visitChildren(bulletList);
        listDepth--;
        append("\n");
    }

    @Override
    public void visit(OrderedList orderedList) {
        listDepth++;
        orderedCounters[listDepth] = orderedList.getMarkerStartNumber();
        visitChildren(orderedList);
        listDepth--;
        append("\n");
    }

    @Override
    public void visit(ListItem listItem) {
        StringBuilder prefix = new StringBuilder();
        prefix.repeat("  ", Math.max(0, listDepth - 1));
        if (orderedCounters[listDepth] > 0) {
            prefix.append(orderedCounters[listDepth]).append(". ");
            orderedCounters[listDepth]++;
        } else {
            prefix.append("• ");
        }
        append(prefix.toString());
        visitChildren(listItem);
    }

    @Override
    public void visit(BlockQuote blockQuote) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setItalic(currentStyle, true);
        StyleConstants.setForeground(currentStyle, new Color(90, 90, 90));
        visitChildren(blockQuote);
        popStyle(saved);
    }

    @Override
    public void visit(FencedCodeBlock fencedCodeBlock) {
        codeBlock(fencedCodeBlock.getLiteral());
    }

    @Override
    public void visit(IndentedCodeBlock indentedCodeBlock) {
        codeBlock(indentedCodeBlock.getLiteral());
    }

    @Override
    public void visit(ThematicBreak thematicBreak) {
        append("────────────────────────────────────────\n");
    }

    @Override
    public void visit(HtmlBlock htmlBlock) {
        String trimmed = htmlBlock.getLiteral().trim();
        Matcher m = ANCHOR_PAIR.matcher(trimmed);
        if (m.matches()) {
            anchorOffsets.put(m.group(2), doc.getLength());
            return;
        }
        pendingAnchorClose = null;
        append(htmlBlock.getLiteral());
        append("\n");
    }

    @Override
    public void visit(CustomBlock customBlock) {
        if (customBlock instanceof TableBlock table) {
            renderTable(table);
        }
    }

    private void renderTable(TableBlock table) {
        List<List<String>> rows = new ArrayList<>();
        for (Node child = table.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableHead || child instanceof TableBody) {
                for (Node rowNode = child.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                    if (rowNode instanceof TableRow) {
                        List<String> cells = new ArrayList<>();
                        for (Node cellNode = rowNode.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                            if (cellNode instanceof TableCell) {
                                cells.add(cellText((TableCell) cellNode));
                            }
                        }
                        if (!cells.isEmpty()) rows.add(cells);
                    }
                }
            }
        }
        if (rows.isEmpty()) return;

        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setFontFamily(currentStyle, Font.MONOSPACED);
        int cols = 0;
        for (List<String> row : rows) cols = Math.max(cols, row.size());
        int[] widths = new int[cols];
        for (List<String> row : rows) {
            for (int i = 0; i < cols && i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }

        append(border('+', '+', '+', widths) + "\n");
        boolean first = true;
        for (List<String> row : rows) {
            StyleConstants.setBold(currentStyle, first);
            append(rowLine(row, widths, cols) + "\n");
            StyleConstants.setBold(currentStyle, false);
            if (first) {
                append(border('+', '+', '+', widths) + "\n");
                first = false;
            }
        }
        append(border('+', '+', '+', widths) + "\n\n");
        popStyle(saved);
    }

    private String border(char left, char mid, char right, int[] widths) {
        StringBuilder sb = new StringBuilder().append(left);
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) sb.append(mid);
            sb.repeat("-", widths[i] + 2);
        }
        return sb.append(right).toString();
    }

    private String rowLine(List<String> row, int[] widths, int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cols; i++) {
            String cell = i < row.size() ? row.get(i) : "";
            sb.append(" ").append(cell);
            sb.repeat(" ", widths[i] - cell.length());
            sb.append(" |");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------
    //  helpers
    // ---------------------------------------------------------------

    private void codeBlock(String literal) {
        append("\n");
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setFontFamily(currentStyle, Font.MONOSPACED);
        currentStyle.addAttribute(CODE_BLOCK_ATTR, Boolean.TRUE);
        String content = literal == null ? "" : literal;
        if (!content.isEmpty() && !content.endsWith("\n")) content += "\n";
        append(content);
        popStyle(saved);
        append("\n\n");
    }

    private int headingSize(int level) {
        return Math.max(baseFontSize, baseFontSize + (6 - level) * 2);
    }

    private String cellText(TableCell cell) {
        StringBuilder sb = new StringBuilder();
        collectText(cell, sb);
        return sb.toString().trim();
    }

    private void collectText(Node node, StringBuilder sb) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            switch (child) {
                case Text t -> sb.append(t.getLiteral());
                case Code c -> sb.append(c.getLiteral());
                case SoftLineBreak softLineBreak -> sb.append(' ');
                default -> collectText(child, sb);
            }
        }
    }

    private String headingText(Node heading) {
        StringBuilder sb = new StringBuilder();
        collectText(heading, sb);
        String text = sb.toString().replaceAll("(?s)<[^>]+>", "");
        return text.trim();
    }

    private SimpleAttributeSet pushStyle() {
        SimpleAttributeSet saved = currentStyle;
        currentStyle = new SimpleAttributeSet(saved);
        return saved;
    }

    private void popStyle(SimpleAttributeSet saved) {
        currentStyle = saved;
    }

    private void append(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            doc.insertString(doc.getLength(), text, currentStyle);
        } catch (BadLocationException e) {
            MAPI.getAPI().logging().logToOutput("MarkdownRenderer BadLocationException: " + e.getMessage());
        }
    }
}
