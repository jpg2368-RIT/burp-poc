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

/**
 * Renders Markdown text into a Swing {@link StyledDocument} for the chat panel.
 *
 * <p>Visits the commonmark parse tree with custom formatting for headings, code
 * blocks, tables, lists, links, and block quotes. GFM tables and autolinks are
 * enabled. Output is styled with simple {@link SimpleAttributeSet} attributes;
 * code blocks and links also carry custom attributes (see {@link #CODE_BLOCK_ATTR}
 * and {@link #LINK_ATTR}) that the chat panel uses for highlighting and click
 * handling.</p>
 *
 * <p>The renderer is a singleton per chat tab. State is reset at the start of
 * every {@link #render} call.</p>
 */
public class MarkdownRenderer extends AbstractVisitor {
    /** Custom attribute marking text inside a code block. */
    public static final String CODE_BLOCK_ATTR = "codeBlock";
    /** Custom attribute holding the destination URL of a rendered link. */
    public static final String LINK_ATTR = "linkUrl";
    /** Text color used for rendered links. */
    private Color linkColor = new Color(0, 90, 200);

    /** Matches a standalone anchor open tag, e.g. {@code <a id="section-1">}. */
    private static final Pattern ANCHOR_OPEN = Pattern.compile("(?i)^<(a|span)\\b[^>]*\\b(?:id|name)\\s*=\\s*\"([^\"]+)\"[^>]*>$");
    /** Matches a standalone anchor close tag, e.g. {@code </a>}. */
    private static final Pattern ANCHOR_CLOSE = Pattern.compile("(?i)^</(a|span)>$");
    /** Matches an empty anchor pair, e.g. {@code <a id="section-1"></a>}. */
    private static final Pattern ANCHOR_PAIR = Pattern.compile("(?i)<(a|span)\\b[^>]*\\b(?:id|name)\\s*=\\s*\"([^\"]+)\"[^>]*></(?:a|span)>");

    /** Heading anchors mapped from slug to document offset. */
    private final Map<String, Integer> anchorOffsets = new HashMap<>();
    /** The commonmark parser with GFM tables and autolinks enabled. */
    private final Parser parser;
    /** The document currently being rendered into. */
    private StyledDocument doc;
    /** Base font size in points; headers scale up from this. */
    private static final int BASE_FONT_SIZE = 12;
    /** The style applied to text appended during the current visit. */
    private SimpleAttributeSet currentStyle = new SimpleAttributeSet();
    /** Current nesting depth of lists (0 when not inside a list). */
    private int listDepth = 0;
    /** Per-depth counters for ordered lists. */
    private final int[] orderedCounters = new int[8];
    /** Generates heading ids for anchor links. */
    private IdGenerator idGen;
    /** Tag of the anchor whose close tag is pending. */
    private String pendingAnchorClose;

    /**
     * Builds the renderer with a commonmark parser that supports GFM tables and
     * autolinks.
     */
    public MarkdownRenderer() {
        parser = Parser.builder()
                .extensions(List.of(TablesExtension.create(), AutolinkExtension.create()))
                .build();
    }

    /**
     * Sets the color used for rendered links.
     *
     * @param color the link color; null keeps the current color
     */
    public void setLinkColor(Color color) {
        if (color != null) linkColor = color;
    }

    /**
     * Returns a copy of the heading-anchor map.
     *
     * @return map from anchor slug to document offset
     */
    public Map<String, Integer> anchorOffsets() {
        return new HashMap<>(anchorOffsets);
    }

    /**
     * Clears all registered heading anchors. Used when the chat is cleared.
     */
    public void clearAnchors() {
        LogManager.complete("clearAnchors()");
        anchorOffsets.clear();
    }

    /**
     * Renders a Markdown string into a document.
     *
     * <p>Resets list depth, list counters, anchors, and pending anchor state,
     * then parses and visits the text. Empty input is skipped.</p>
     *
     * @param doc      the document to append to
     * @param markdown the Markdown text to render; empty or null does nothing
     */
    public void render(StyledDocument doc, String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            LogManager.complete("render(): empty input, skip");
            return;
        }
        LogManager.complete("render(): " + markdown.length() + " chars");
        this.doc = doc;
        listDepth = 0;
        Arrays.fill(orderedCounters, 0);
        idGen = IdGenerator.builder().build();
        pendingAnchorClose = null;
        long startNanos = System.nanoTime();
        Node root = parser.parse(normalizeTables(markdown));
        root.accept(this);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        LogManager.complete("render(): parsed+visited in " + elapsedMs + " ms, anchors=" + anchorOffsets.size()
                + ", doc length=" + doc.getLength());
    }

    // ---------------------------------------------------------------
    //  lenient table detection: insert a GFM delimiter row when the
    //  LLM omits it, so pipe blocks still parse as tables
    // ---------------------------------------------------------------

    /**
     * Inserts missing GFM delimiter rows into pipe tables.
     *
     * <p>LLM output often omits the delimiter row (the second header line).
     * When a pipe line is followed by another pipe line that is not a delimiter
     * row, a delimiter row is generated so commonmark parses the block as a
     * table instead of a paragraph.</p>
     *
     * @param markdown the raw Markdown text
     * @return the text with repaired tables
     */
    private String normalizeTables(String markdown) {
        String[] lines = markdown.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        int tablesRepaired = 0;
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
                tablesRepaired++;
            } else {
                out.addAll(Arrays.asList(lines).subList(i, runEnd));
            }
            i = runEnd;
        }
        String result = String.join("\n", out);
        if (tablesRepaired > 0) {
            LogManager.complete("normalizeTables: repaired " + tablesRepaired + " pipe tables ("
                    + lines.length + " lines -> " + out.size() + " lines)");
        }
        return result;
    }

    /**
     * Checks whether a line looks like a pipe table line.
     *
     * @param line the line to check
     * @return true when the trimmed line contains a pipe character
     */
    private boolean isPipeLine(String line) {
        String t = line.trim();
        return t.contains("|");
    }

    /**
     * Checks whether a line is a GFM delimiter row.
     *
     * <p>A valid delimiter row has cells containing only dashes and colons, with
     * at least one dash per cell.</p>
     *
     * @param line the line to check
     * @return true when the line is a delimiter row
     */
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

    /**
     * Counts the cells in a header line.
     *
     * @param headerLine the header line
     * @return the number of cells, at least 1
     */
    private int columnCount(String headerLine) {
        String t = headerLine.trim();
        String[] parts = t.split("\\|", -1);
        int cols = parts.length;
        if (t.startsWith("|")) cols--;
        if (t.endsWith("|")) cols--;
        return Math.max(1, cols);
    }

    /**
     * Builds a GFM delimiter row for a header line.
     *
     * <p>Keeps the header's leading indentation and creates one " --- " cell
     * per column.</p>
     *
     * @param headerLine the header line to match
     * @return the generated delimiter row
     */
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

    /**
     * Appends plain text to the document.
     *
     * @param text the text node
     */
    @Override
    public void visit(Text text) {
        append(text.getLiteral());
    }

    /**
     * Appends inline code in a monospaced font.
     *
     * @param code the inline code node
     */
    @Override
    public void visit(Code code) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setFontFamily(currentStyle, Font.MONOSPACED);
        append(code.getLiteral());
        popStyle(saved);
    }

    /**
     * Appends emphasized text in italics.
     *
     * @param emphasis the emphasis node
     */
    @Override
    public void visit(Emphasis emphasis) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setItalic(currentStyle, true);
        visitChildren(emphasis);
        popStyle(saved);
    }

    /**
     * Appends strong emphasis text in bold.
     *
     * @param strongEmphasis the strong emphasis node
     */
    @Override
    public void visit(StrongEmphasis strongEmphasis) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setBold(currentStyle, true);
        visitChildren(strongEmphasis);
        popStyle(saved);
    }

    /**
     * Appends a link in the link color with an underline, carrying the
     * destination URL as the {@link #LINK_ATTR} attribute.
     *
     * @param link the link node
     */
    @Override
    public void visit(Link link) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setForeground(currentStyle, linkColor);
        StyleConstants.setUnderline(currentStyle, true);
        currentStyle.addAttribute(LINK_ATTR, link.getDestination());
        LogManager.complete("render link: " + link.getDestination());
        visitChildren(link);
        popStyle(saved);
    }

    /**
     * Renders images as a placeholder text since the chat panel cannot display
     * them.
     *
     * @param image the image node
     */
    @Override
    public void visit(Image image) {
        if (image.getTitle() != null) append("[" + image.getTitle() + "]");
        else if (image.getDestination() != null) append("[image]");
    }

    /**
     * Converts a soft line break to a newline.
     *
     * @param softLineBreak the soft line break node
     */
    @Override
    public void visit(SoftLineBreak softLineBreak) {
        append("\n");
    }

    /**
     * Converts a hard line break to a newline.
     *
     * @param hardLineBreak the hard line break node
     */
    @Override
    public void visit(HardLineBreak hardLineBreak) {
        append("\n");
    }

    /**
     * Handles inline HTML anchors and passes through all other inline HTML.
     *
     * <p>Open and close anchor tags are consumed and the anchor id is registered
     * with the current document offset. Empty anchor pairs are handled here;
     * block-level anchor pairs are handled in {@link #visit(HtmlBlock)}.</p>
     *
     * @param htmlInline the inline HTML node
     */
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
            LogManager.complete("Anchor registered: id=\"" + open.group(2) + "\" at offset " + doc.getLength());
            return;
        }
        pendingAnchorClose = null;
        append(literal);
    }

    // ---------------------------------------------------------------
    //  blocks
    // ---------------------------------------------------------------

    /**
     * Renders a paragraph followed by a blank line.
     *
     * @param paragraph the paragraph node
     */
    @Override
    public void visit(Paragraph paragraph) {
        visitChildren(paragraph);
        append("\n");
    }

    /**
     * Renders a heading in bold with a scaled font size and registers an anchor
     * for in-document links.
     *
     * @param heading the heading node
     */
    @Override
    public void visit(Heading heading) {
        int start = doc.getLength();
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setBold(currentStyle, true);
        StyleConstants.setFontSize(currentStyle, headingSize(heading.getLevel()));
        visitChildren(heading);
        popStyle(saved);
        String text = headingText(heading);
        String id = null;
        if (idGen != null && !text.isEmpty()) {
            id = idGen.generateId(text);
            anchorOffsets.put(id, start);
        }
        LogManager.complete("Heading level " + heading.getLevel() + ": \"" + text + "\""
                + (id != null ? " (id=" + id + " at offset " + start + ")" : ""));
        append("\n\n");
    }

    /**
     * Renders a bullet list, tracking the nesting depth.
     *
     * @param bulletList the bullet list node
     */
    @Override
    public void visit(BulletList bulletList) {
        listDepth++;
        visitChildren(bulletList);
        listDepth--;
        append("\n");
    }

    /**
     * Renders an ordered list, resetting the counter at the list's depth.
     *
     * @param orderedList the ordered list node
     */
    @Override
    public void visit(OrderedList orderedList) {
        listDepth++;
        orderedCounters[listDepth] = orderedList.getMarkerStartNumber();
        visitChildren(orderedList);
        listDepth--;
        append("\n");
    }

    /**
     * Renders a list item with an indentation prefix and a bullet or number.
     *
     * @param listItem the list item node
     */
    @Override
    public void visit(ListItem listItem) {
        StringBuilder prefix = new StringBuilder();
        prefix.repeat("  ", Math.max(0, listDepth - 1));
        if (orderedCounters[listDepth] > 0) {
            prefix.append(orderedCounters[listDepth]).append(". ");
            orderedCounters[listDepth]++;
        } else {
            prefix.append("â€¢ ");
        }
        append(prefix.toString());
        visitChildren(listItem);
    }

    /**
     * Renders a block quote in italics and a muted color.
     *
     * @param blockQuote the block quote node
     */
    @Override
    public void visit(BlockQuote blockQuote) {
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setItalic(currentStyle, true);
        StyleConstants.setForeground(currentStyle, new Color(90, 90, 90));
        visitChildren(blockQuote);
        popStyle(saved);
    }

    /**
     * Renders a fenced code block.
     *
     * @param fencedCodeBlock the fenced code block node
     */
    @Override
    public void visit(FencedCodeBlock fencedCodeBlock) {
        codeBlock(fencedCodeBlock.getLiteral());
    }

    /**
     * Renders an indented code block.
     *
     * @param indentedCodeBlock the indented code block node
     */
    @Override
    public void visit(IndentedCodeBlock indentedCodeBlock) {
        codeBlock(indentedCodeBlock.getLiteral());
    }

    /**
     * Renders a thematic break (horizontal rule) as a line of box-drawing
     * characters.
     *
     * @param thematicBreak the thematic break node
     */
    @Override
    public void visit(ThematicBreak thematicBreak) {
        append("â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€\n");
    }

    /**
     * Handles a block-level anchor pair and passes through all other HTML blocks.
     *
     * @param htmlBlock the HTML block node
     */
    @Override
    public void visit(HtmlBlock htmlBlock) {
        String trimmed = htmlBlock.getLiteral().trim();
        Matcher m = ANCHOR_PAIR.matcher(trimmed);
        if (m.matches()) {
            anchorOffsets.put(m.group(2), doc.getLength());
            LogManager.complete("Anchor pair registered: id=\"" + m.group(2) + "\" at offset " + doc.getLength());
            return;
        }
        pendingAnchorClose = null;
        append(htmlBlock.getLiteral());
        append("\n");
    }

    /**
     * Renders custom blocks; only GFM tables are supported here.
     *
     * @param customBlock the custom block node
     */
    @Override
    public void visit(CustomBlock customBlock) {
        if (customBlock instanceof TableBlock table) {
            renderTable(table);
        }
    }

    /**
     * Renders a GFM table as a monospaced ASCII table.
     *
     * <p>Column widths are computed from the widest cell. The header row is
     * bold; borders separate the header from the body.</p>
     *
     * @param table the table block node
     */
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

        append(border(widths) + "\n");
        boolean first = true;
        for (List<String> row : rows) {
            StyleConstants.setBold(currentStyle, first);
            append(rowLine(row, widths, cols) + "\n");
            StyleConstants.setBold(currentStyle, false);
            if (first) {
                append(border(widths) + "\n");
                first = false;
            }
        }
        append(border(widths) + "\n\n");
        popStyle(saved);
    }

    /**
     * Builds one ASCII table border line.
     *
     * @param widths the column widths
     * @return the border line
     */
    private String border(int[] widths) {
        StringBuilder sb = new StringBuilder().append('+');
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) sb.append('+');
            sb.repeat("-", widths[i] + 2);
        }
        return sb.append('+').toString();
    }

    /**
     * Builds one padded table row line.
     *
     * @param row    the cell values of the row
     * @param widths the target column widths
     * @param cols   the total column count; missing cells are padded empty
     * @return the row line
     */
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

/**
     * Appends a code block in a monospaced font, marked with the
     * {@link #CODE_BLOCK_ATTR} attribute for highlight painting.
     *
     * @param literal the code block content
     */
    private void codeBlock(String literal) {
        append("\n");
        SimpleAttributeSet saved = pushStyle();
        StyleConstants.setFontFamily(currentStyle, Font.MONOSPACED);
        currentStyle.addAttribute(CODE_BLOCK_ATTR, Boolean.TRUE);
        String content = literal == null ? "" : literal;
        if (!content.isEmpty() && !content.endsWith("\n")) content += "\n";
        LogManager.complete("Code block: " + content.length() + " chars");
        append(content);
        popStyle(saved);
        append("\n\n");
    }

    /**
     * Computes the font size for a heading level.
     *
     * <p>Lower levels (closer to level 1) get larger sizes.</p>
     *
     * @param level the heading level, 1 to 6
     * @return the font size in points
     */
    private int headingSize(int level) {
        return Math.max(BASE_FONT_SIZE, BASE_FONT_SIZE + (6 - level) * 2);
    }

    /**
     * Extracts the plain text of a table cell.
     *
     * @param cell the table cell node
     * @return the trimmed cell text
     */
    private String cellText(TableCell cell) {
        StringBuilder sb = new StringBuilder();
        collectText(cell, sb);
        return sb.toString().trim();
    }

    /**
     * Collects plain text from a node's children.
     *
     * <p>Text and inline code literals are appended; soft line breaks become
     * spaces. All other node types are descended into recursively.</p>
     *
     * @param node the node to traverse
     * @param sb   the buffer to append to
     */
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

    /**
     * Extracts the plain text of a heading, with any inline HTML stripped.
     *
     * @param heading the heading node
     * @return the trimmed heading text without HTML tags
     */
    private String headingText(Node heading) {
        StringBuilder sb = new StringBuilder();
        collectText(heading, sb);
        String text = sb.toString().replaceAll("(?s)<[^>]+>", "");
        return text.trim();
    }

    /**
     * Saves the current style and starts a new style derived from it.
     *
     * @return the previous style, to be restored with {@link #popStyle}
     */
    private SimpleAttributeSet pushStyle() {
        SimpleAttributeSet saved = currentStyle;
        currentStyle = new SimpleAttributeSet(saved);
        return saved;
    }

    /**
     * Restores the style saved by {@link #pushStyle}.
     *
     * @param saved the previously saved style
     */
    private void popStyle(SimpleAttributeSet saved) {
        currentStyle = saved;
    }

    /**
     * Inserts text at the end of the document with the current style.
     *
     * @param text the text to append; empty or null does nothing
     */
    private void append(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            doc.insertString(doc.getLength(), text, currentStyle);
        } catch (BadLocationException e) {
            LogManager.error("MarkdownRenderer BadLocationException: " + e.getMessage());
        }
    }
}
