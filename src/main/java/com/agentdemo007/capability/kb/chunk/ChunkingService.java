package com.agentdemo007.capability.kb.chunk;

import com.agentdemo007.capability.kb.parse.ParsedDocument;
import com.agentdemo007.capability.kb.parse.ParsedSection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 切块服务（[[kb-ingest-design]]·任务2）：结构感知 + 双策略。
 *
 * <p><b>策略选择（自动）</b>：全文条款编号标记 ≥3 处 → <b>条款感知切块</b>（专业领域：
 * 法务/政策/规章类知识以"条款"为语义原子——单条政策被拦腰切开必然两块都答不对，
 * 评分再高也救不回来）；否则 → <b>通用递归切块</b>（段落打包 + 超长递归分隔）。
 *
 * <p><b>条款感知切块</b>：按行首编号标记（第X条 / 1.2 / （一） / 1、）切出条款块——
 * <ul>
 *   <li>条款原子不硬切：小块同段聚簇打包（簇 ≤ maxChars），簇间不共享条款；</li>
 *   <li>超长单条内部递归切分时加"（承接第X条）"前缀，检索命中任意分段都能锚定原条款；</li>
 *   <li>面包屑 = 文档标题 › 节标题 › 条款号（嵌入与 BM25 双通道受益）。</li>
 * </ul>
 * <p><b>通用递归切块</b>：按段落（\n）打包进 maxChars；超长段落按分隔符梯度
 * （句号→分号→逗号→顿号→空格→硬切）递归切分并回拖 overlap 重叠窗口。
 * 面包屑 = 标题 › 节标题路径。短尾块并入前块（避免碎块稀释 topK）。
 */
@Component
public class ChunkingService {

    /** 行首条款编号标记：第X条 / 1.2 / （一） / (3) / 一、 / 1、 / 第X章。 */
    private static final Pattern CLAUSE_HEAD = Pattern.compile(
            "^\\s*(第[一二三四五六七八九十百千0-9]+[条章节]|\\d+(\\.\\d+){1,3}[、.．]?|[一二三四五六七八九十]+、"
                    + "|（[一二三四五六七八九十0-9]+）|\\([0-9]{1,2}\\)|\\d{1,3}、)");

    /** 超长文本递归切分分隔符梯度（先语义强后弱，最后硬切）。 */
    private static final String[] SEPARATORS = {"\n", "。", "！", "？", "；", ";", ".", "，", ",", "、", " ", ""};

    public List<ProposedChunk> chunk(ParsedDocument doc, ChunkOptions opts) {
        List<ProposedChunk> out = new ArrayList<>();
        int seq = 1;
        boolean clauseMode = detectClauseMode(doc, opts);
        for (ParsedSection section : doc.sections()) {
            String body = section.text() == null ? "" : section.text().strip();
            if (body.isEmpty()) {
                continue;
            }
            String prefix = breadcrumb(doc.title(), section.headingPath());
            List<String> pieces = clauseMode ? clausePieces(body, prefix, opts) : paragraphPieces(body, opts);
            for (String piece : pieces) {
                String text = piece.strip();
                if (text.isEmpty()) {
                    continue;
                }
                out.add(new ProposedChunk(seq++, prefix.isEmpty() ? "" : prefix, text));
            }
        }
        if (out.isEmpty()) {
            out.add(new ProposedChunk(1, "", ""));
        }
        return out;
    }

    // ---- 条款感知（专业领域切块）----

    /** 条款模式判定：条款标记行 ≥3 即视为条款型文档（政策/合同/规章）。 */
    public static boolean detectClauseMode(ParsedDocument doc, ChunkOptions opts) {
        int marks = 0;
        for (ParsedSection section : doc.sections()) {
            String text = section.text() == null ? "" : section.text();
            for (String line : text.split("\n")) {
                if (CLAUSE_HEAD.matcher(line).find()) {
                    marks++;
                }
            }
        }
        return marks >= 3;
    }

    /**
     * 条件切块：条款聚簇打包（每簇 ≤ maxChars，簇内条款保序完整）；
     * 超长条款内部递归切分（承接前缀锚定原条款）。
     */
    private static List<String> clausePieces(String body, String prefix, ChunkOptions opts) {
        List<Clause> clauses = splitClauses(body);
        List<String> out = new ArrayList<>();
        StringBuilder cluster = new StringBuilder();
        for (Clause c : clauses) {
            String label = c.label() == null ? "" : c.label().strip();
            // 单条超限 → 先收簇，再递归切单条
            if (c.text().length() > opts.maxChars()) {
                if (!cluster.isEmpty()) {
                    out.add(cluster.toString());
                    cluster.setLength(0);
                }
                List<String> parts = splitText(c.text(), opts.maxChars(), opts.overlap());
                for (int i = 0; i < parts.size(); i++) {
                    String part = parts.get(i).strip();
                    if (part.isEmpty()) {
                        continue;
                    }
                    out.add((i > 0 && !label.isEmpty() ? "（承接" + label + "）" : "") + part);
                }
                continue;
            }
            // 簇满 → 收簇开新簇
            if (!cluster.isEmpty() && cluster.length() + c.text().length() + 1 > opts.maxChars()) {
                out.add(cluster.toString());
                cluster.setLength(0);
            }
            if (!cluster.isEmpty()) {
                cluster.append('\n');
            }
            cluster.append(c.text());
        }
        if (!cluster.isEmpty()) {
            out.add(cluster.toString());
        }
        return out;
    }

    private record Clause(String label, String text) {
    }

    /** 按行首条款标记切块（标记前的导语行归首个条款块；无标记的连续段落整体一块）。 */
    private static List<Clause> splitClauses(String body) {
        List<Clause> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String curLabel = null;
        for (String line : body.split("\n", -1)) {
            Matcher m = CLAUSE_HEAD.matcher(line);
            if (m.find()) {
                if (!cur.isEmpty()) {
                    out.add(new Clause(curLabel, cur.toString().strip()));
                    cur.setLength(0);
                }
                curLabel = m.group(1).strip();
            }
            if (!line.isBlank()) {
                cur.append(line).append('\n');
            }
        }
        if (!cur.isEmpty()) {
            out.add(new Clause(curLabel, cur.toString().strip()));
        }
        if (out.isEmpty()) {
            out.add(new Clause(null, body.strip()));
        }
        return out;
    }

    // ---- 通用递归切块----

    /** 段落打包：段落为原子聚进块（≤ maxChars）；超长段落递归切分（overlap 回拖）。 */
    private static List<String> paragraphPieces(String body, ChunkOptions opts) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String para : body.split("\n+", -1)) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > opts.maxChars()) {
                if (!buf.isEmpty()) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                out.addAll(splitText(p, opts.maxChars(), opts.overlap()));
                continue;
            }
            if (!buf.isEmpty() && buf.length() + p.length() + 1 > opts.maxChars()) {
                out.add(buf.toString());
                buf.setLength(0);
            }
            if (!buf.isEmpty()) {
                buf.append('\n');
            }
            buf.append(p);
        }
        if (!buf.isEmpty()) {
            out.add(buf.toString());
        }
        // 短尾块并入前块（≤ maxChars/4 的碎块不单独占 topK 名额）
        if (out.size() > 1) {
            String last = out.get(out.size() - 1);
            String prev = out.get(out.size() - 2);
            if (last.length() <= opts.maxChars() / 4 && prev.length() + last.length() + 1 <= opts.maxChars()) {
                out.set(out.size() - 2, prev + "\n" + last);
                out.remove(out.size() - 1);
            }
        }
        return out;
    }

    /**
     * 递归切分超长文本：按分隔符梯度找 ≤ max 的最近切点；下一窗口回拖 overlap
     * 并对齐到分隔符（硬切兜底）。空串安全。
     */
    static List<String> splitText(String text, int max, int overlap) {
        List<String> out = new ArrayList<>();
        String rest = text.strip();
        while (rest.length() > max) {
            int cut = findCut(rest, max);
            out.add(rest.substring(0, cut).strip());
            int next = Math.max(cut - overlap, 0);
            if (next > 0) {
                next = alignToSeparator(rest, next); // 重叠窗口起点对齐语义边界
            }
            rest = rest.substring(next).strip();
        }
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }

    /** 在 [max/2, max] 内从后往前找分隔符切点；找不到硬切 max（再兜底 max/2 防死循环）。 */
    private static int findCut(String text, int max) {
        for (String sep : SEPARATORS) {
            if (sep.isEmpty()) {
                break;
            }
            int idx = text.lastIndexOf(sep, max);
            if (idx >= max / 2) {
                return idx + sep.length();
            }
        }
        return max;
    }

    /** 重叠窗口起点回退到最近分隔符之后（避免块首残句）。 */
    private static int alignToSeparator(String text, int pos) {
        for (String sep : SEPARATORS) {
            if (sep.isEmpty()) {
                break;
            }
            int idx = text.lastIndexOf(sep, pos);
            if (idx > 0) {
                return Math.min(idx + sep.length(), pos);
            }
        }
        return pos;
    }

    /** 面包屑：文档标题 › 节标题路径（入库时由 ProposedChunk.indexedText 拼装；路径首段与标题重复时去重）。 */
    private static String breadcrumb(String title, List<String> headingPath) {
        List<String> parts = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            parts.add(title.strip());
        }
        for (String h : headingPath) {
            if (h == null || h.isBlank()) {
                continue;
            }
            String trimmed = h.strip();
            if (!parts.isEmpty() && trimmed.equals(parts.get(0))) {
                continue; // H1 标题已被解析器收进 headingPath（title 从 H1 提取）时避免重复
            }
            parts.add(trimmed);
        }
        return String.join(" › ", parts);
    }
}
