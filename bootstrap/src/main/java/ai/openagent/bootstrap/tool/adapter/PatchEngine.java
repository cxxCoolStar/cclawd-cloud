package ai.openagent.bootstrap.tool.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * apply_patch 的存储无关补丁引擎。
 *
 * <p>
 * 采用两阶段语义：先解析补丁并在内存中完成全部 hunk 锚定，
 * 成功后才会产出写/删计划，确保不会部分写入。
 * </p>
 *
 * <p>
 * 支持 OpenAI Codex DSL：Add File / Update File（多 hunk、可选
 * Move to、End of File 锚定）/ Delete File。hunk 锚定按 identity →
 * 去尾空白 → 全 trim → Unicode 归一化 四级渐进宽松匹配；
 * context 行取文件实际文本（模糊匹配命中时不覆写原有空白与字形）
 * </p>
 */
final class PatchEngine {

    private static final String BEGIN_PATCH = "*** Begin Patch";
    private static final String END_PATCH = "*** End Patch";
    private static final String ADD_PREFIX = "*** Add File: ";
    private static final String UPDATE_PREFIX = "*** Update File: ";
    private static final String DELETE_PREFIX = "*** Delete File: ";
    private static final String MOVE_TO_PREFIX = "*** Move to: ";
    private static final String END_OF_FILE = "*** End of File";
    private static final String HUNK_SEP = "@@";

    private PatchEngine() {}

    /**
     * 补丁解析或锚定失败（消息含出错行/期望行，供模型自纠）
     */
    static final class PatchException extends RuntimeException {
        PatchException(String message) {
            super(message);
        }
    }

    enum OpType {
        ADD,
        UPDATE,
        DELETE
    }

    enum LineKind {
        CONTEXT,
        ADD,
        REMOVE
    }

    record HunkLine(LineKind kind, String text) {}

    static final class Hunk {
        final List<HunkLine> lines = new ArrayList<>();
        boolean isEof;
    }

    static final class PatchOp {
        final OpType type;
        final String path;
        String moveTo = "";
        StringBuilder addBody = new StringBuilder();
        final List<Hunk> hunks = new ArrayList<>();

        PatchOp(OpType type, String path) {
            this.type = type;
            this.path = path;
        }
    }

    record PlannedWrite(String path, String content) {}

    record Plan(List<PatchOp> ops, List<PlannedWrite> writes, List<String> deletes) {}

    /**
     * 文件读取回调（Update 需要旧内容做 hunk 锚定）
     */
    interface FileReader {
        String read(String path);
    }

    /**
     * 解析补丁并在内存中完成全部 hunk 锚定，产出写/删计划；
     * 任何 hunk 失败都在落盘前抛出（无部分写入）
     */
    static Plan plan(String input, FileReader reader) {
        List<PatchOp> ops = parse(input);
        List<PlannedWrite> writes = new ArrayList<>();
        List<String> deletes = new ArrayList<>();
        for (PatchOp op : ops) {
            switch (op.type) {
                case ADD -> {
                    requirePath(op, "Add File");
                    writes.add(new PlannedWrite(op.path, op.addBody.toString()));
                }
                case DELETE -> {
                    requirePath(op, "Delete File");
                    deletes.add(op.path);
                }
                case UPDATE -> {
                    requirePath(op, "Update File");
                    String old = reader.read(op.path);
                    String updated = applyHunks(op.path, old, op.hunks);
                    String target = op.path;
                    if (!op.moveTo.isEmpty() && !op.moveTo.equals(op.path)) {
                        target = op.moveTo;
                        deletes.add(op.path);
                    }
                    writes.add(new PlannedWrite(target, updated));
                }
            }
        }
        return new Plan(ops, writes, deletes);
    }

    /**
     * 生成补丁操作的结果摘要，输出格式：A（Add）、D（Delete）、U（Update）、M（Move）行。
     */
    static String summary(List<PatchOp> ops) {
        StringBuilder sb = new StringBuilder();
        for (PatchOp op : ops) {
            switch (op.type) {
                case ADD -> sb.append("A ").append(op.path).append('\n');
                case DELETE -> sb.append("D ").append(op.path).append('\n');
                case UPDATE -> {
                    if (!op.moveTo.isEmpty() && !op.moveTo.equals(op.path)) {
                        sb.append("M ").append(op.path).append(" -> ").append(op.moveTo)
                                .append(" (").append(op.hunks.size()).append(" hunk(s))\n");
                    } else {
                        sb.append("U ").append(op.path)
                                .append(" (").append(op.hunks.size()).append(" hunk(s))\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void requirePath(PatchOp op, String opName) {
        if (op.path.isEmpty()) {
            throw new PatchException("apply_patch: " + opName + " requires a non-empty path");
        }
    }

    // ==================== 解析器 ====================

    private static List<PatchOp> parse(String input) {
        String trimmed = input.strip();
        if (!trimmed.startsWith(BEGIN_PATCH)) {
            throw new PatchException("apply_patch: input must start with \"" + BEGIN_PATCH + "\"");
        }
        String[] lines = trimmed.split("\n", -1);

        List<PatchOp> ops = new ArrayList<>();
        PatchOp[] current = {null};
        Hunk[] currentHunk = {null};
        boolean seenEnd = false;

        Runnable flushOp = () -> {
            if (current[0] == null) {
                return;
            }
            if (currentHunk[0] != null) {
                current[0].hunks.add(currentHunk[0]);
                currentHunk[0] = null;
            }
            ops.add(current[0]);
            current[0] = null;
        };

        loop:
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().equals(END_PATCH)) {
                flushOp.run();
                seenEnd = true;
                break loop;
            } else if (line.startsWith(ADD_PREFIX)) {
                flushOp.run();
                current[0] = new PatchOp(OpType.ADD, line.substring(ADD_PREFIX.length()).strip());
            } else if (line.startsWith(UPDATE_PREFIX)) {
                flushOp.run();
                current[0] = new PatchOp(OpType.UPDATE, line.substring(UPDATE_PREFIX.length()).strip());
            } else if (line.startsWith(DELETE_PREFIX)) {
                flushOp.run();
                current[0] = new PatchOp(OpType.DELETE, line.substring(DELETE_PREFIX.length()).strip());
            } else if (line.startsWith(MOVE_TO_PREFIX)) {
                if (current[0] == null || current[0].type != OpType.UPDATE) {
                    throw new PatchException(
                            "apply_patch: \"" + line.strip() + "\" outside an Update File block");
                }
                if (!current[0].moveTo.isEmpty()) {
                    throw new PatchException("apply_patch: duplicate Move to: for \"" + current[0].path + "\"");
                }
                if (!current[0].hunks.isEmpty() || currentHunk[0] != null) {
                    throw new PatchException("apply_patch: \"" + line.strip()
                            + "\" must come before any hunk in Update File: \"" + current[0].path + "\"");
                }
                current[0].moveTo = line.substring(MOVE_TO_PREFIX.length()).strip();
            } else if (line.strip().equals(END_OF_FILE)) {
                if (current[0] == null || current[0].type != OpType.UPDATE || currentHunk[0] == null) {
                    throw new PatchException("apply_patch: \"" + END_OF_FILE + "\" not inside an Update hunk");
                }
                currentHunk[0].isEof = true;
                current[0].hunks.add(currentHunk[0]);
                currentHunk[0] = null;
            } else if (line.startsWith(HUNK_SEP)) {
                if (current[0] == null || current[0].type != OpType.UPDATE) {
                    throw new PatchException("apply_patch: \"" + HUNK_SEP + "\" outside an Update File block");
                }
                if (currentHunk[0] != null) {
                    current[0].hunks.add(currentHunk[0]);
                }
                currentHunk[0] = new Hunk();
            } else {
                if (current[0] == null) {
                    if (line.strip().isEmpty()) {
                        continue;
                    }
                    throw new PatchException(
                            "apply_patch: unexpected line outside any file block: \"" + line + "\"");
                }
                switch (current[0].type) {
                    case ADD -> {
                        if (!line.startsWith("+")) {
                            throw new PatchException(
                                    "apply_patch: Add File body line must start with \"+\" (got \"" + line + "\")");
                        }
                        current[0].addBody.append(line.substring(1)).append('\n');
                    }
                    case DELETE -> {
                        if (!line.strip().isEmpty()) {
                            throw new PatchException(
                                    "apply_patch: Delete File expects no body (got \"" + line + "\")");
                        }
                    }
                    case UPDATE -> {
                        if (currentHunk[0] == null) {
                            currentHunk[0] = new Hunk();
                        }
                        if (line.isEmpty()) {
                            // 空行按空白 context 行容忍（LLM 常吞掉空格前缀）
                            currentHunk[0].lines.add(new HunkLine(LineKind.CONTEXT, ""));
                            continue;
                        }
                        switch (line.charAt(0)) {
                            case ' ' -> currentHunk[0].lines.add(
                                    new HunkLine(LineKind.CONTEXT, line.substring(1)));
                            case '+' -> currentHunk[0].lines.add(
                                    new HunkLine(LineKind.ADD, line.substring(1)));
                            case '-' -> currentHunk[0].lines.add(
                                    new HunkLine(LineKind.REMOVE, line.substring(1)));
                            default -> throw new PatchException(
                                    "apply_patch: hunk line must start with ' ', '+', or '-' (got \""
                                            + line + "\")");
                        }
                    }
                }
            }
        }

        if (!seenEnd) {
            throw new PatchException("apply_patch: missing \"" + END_PATCH + "\" sentinel");
        }
        if (ops.isEmpty()) {
            throw new PatchException("apply_patch: empty patch (no file operations)");
        }
        return ops;
    }

    // ==================== 应用器 ====================

    static String applyHunks(String path, String oldContent, List<Hunk> hunks) {
        boolean hadTrailingNewline = oldContent.endsWith("\n");
        List<String> lines = new ArrayList<>();
        if (!oldContent.isEmpty()) {
            String body = hadTrailingNewline
                    ? oldContent.substring(0, oldContent.length() - 1)
                    : oldContent;
            lines.addAll(List.of(body.split("\n", -1)));
        }

        int searchFrom = 0;
        for (int hi = 0; hi < hunks.size(); hi++) {
            Hunk hunk = hunks.get(hi);
            List<String> pattern = patternLines(hunk);
            // 纯新增 hunk（无 context/remove）锚定文件末尾且不推进游标
            boolean anchorEof = hunk.isEof || pattern.isEmpty();

            int index = findHunkAnchor(lines, pattern, anchorEof, searchFrom);
            if (index < 0) {
                throw new PatchException(
                        "apply_patch: hunk #" + (hi + 1) + " in " + path
                                + " did not match — re-read the file and emit a fresh patch.\nExpected lines:\n"
                                + String.join("\n", pattern));
            }

            List<String> replacement = buildReplacement(hunk, lines, index);
            List<String> next = new ArrayList<>(lines.size() - pattern.size() + replacement.size());
            next.addAll(lines.subList(0, index));
            next.addAll(replacement);
            next.addAll(lines.subList(index + pattern.size(), lines.size()));
            lines = next;
            if (!anchorEof) {
                searchFrom = index + replacement.size();
            }
        }

        String out = String.join("\n", lines);
        if (hadTrailingNewline && !out.isEmpty()) {
            out += "\n";
        }
        return out;
    }

    private static List<String> patternLines(Hunk hunk) {
        List<String> out = new ArrayList<>();
        for (HunkLine line : hunk.lines) {
            if (line.kind() == LineKind.CONTEXT || line.kind() == LineKind.REMOVE) {
                out.add(line.text());
            }
        }
        return out;
    }

    /**
     * context 行取文件实际文本（模糊匹配命中时保留原有空白与字形）
     */
    private static List<String> buildReplacement(Hunk hunk, List<String> fileLines, int startIndex) {
        List<String> out = new ArrayList<>();
        int offset = 0;
        for (HunkLine line : hunk.lines) {
            switch (line.kind()) {
                case CONTEXT -> {
                    out.add(fileLines.get(startIndex + offset));
                    offset++;
                }
                case REMOVE -> offset++;
                case ADD -> out.add(line.text());
            }
        }
        return out;
    }

    /**
     * 四级渐进宽松锚定：identity → 去尾空白 → 全 trim → Unicode 归一化。
     * 逐级尝试匹配，直到找到匹配的锚点位置。
     */
    private static int findHunkAnchor(List<String> lines, List<String> pattern, boolean anchorEof, int searchFrom) {
        List<UnaryOperator<String>> transforms = List.of(
                UnaryOperator.identity(),
                PatchEngine::rstripWhitespace,
                String::strip,
                PatchEngine::normalizeForFuzzy);
        for (UnaryOperator<String> transform : transforms) {
            List<String> tl = lines.stream().map(transform).toList();
            List<String> tp = pattern.stream().map(transform).toList();
            if (anchorEof) {
                int start = tl.size() - tp.size();
                if (start >= 0 && linesEqual(tl, start, tp)) {
                    return start;
                }
            }
            int index = seekSequence(tl, tp, searchFrom);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    private static int seekSequence(List<String> haystack, List<String> pattern, int start) {
        if (pattern.isEmpty()) {
            return start <= haystack.size() ? start : -1;
        }
        for (int i = start; i + pattern.size() <= haystack.size(); i++) {
            if (linesEqual(haystack, i, pattern)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean linesEqual(List<String> haystack, int start, List<String> pattern) {
        if (start < 0 || start + pattern.size() > haystack.size()) {
            return false;
        }
        for (int j = 0; j < pattern.size(); j++) {
            if (!haystack.get(start + j).equals(pattern.get(j))) {
                return false;
            }
        }
        return true;
    }

    private static String rstripWhitespace(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * 归一化 LLM 常替换的印刷体字形（破折号/引号/各类空格 → ASCII），
     * 归一化后再 strip，用于模糊匹配。
     */
    private static String normalizeForFuzzy(String s) {
        if (s.chars().allMatch(c -> c < 128)) {
            return s.strip();
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int codePoint = s.codePointAt(i);
            i += Character.charCount(codePoint);
            switch (codePoint) {
                // 各类连字符/破折号
                case '‐', '‑', '‒', '–', '—', '―', '−', '﹘', '﹣', '－' -> sb.append('-');
                // 单引号
                case '‘', '’', '‚', '‛' -> sb.append('\'');
                // 双引号
                case '“', '”', '„', '‟' -> sb.append('"');
                // 各类空格（NBSP/窄空格/表意空格等）
                case ' ', ' ', ' ', ' ', ' ', ' ', ' ',
                        ' ', ' ', ' ', ' ', ' ', ' ', ' ',
                        '　' -> sb.append(' ');
                default -> sb.appendCodePoint(codePoint);
            }
        }
        return sb.toString().strip();
    }
}
