package ai.openagent.agent.eval.grader;

import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.EvalExpected.OutcomeExpected;
import ai.openagent.agent.eval.Grader;
import ai.openagent.agent.eval.GraderResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 结果状态评分器
 * - 检查文件断言（file_exists, file_content_contains, file_content_not_contains）
 * - 检查目录断言（dir_exists）
 */
public class OutcomeStateGrader implements Grader {

    @Override
    public GraderResult grade(EvalCase testCase, EvalContext context) {
        List<String> evidence = new ArrayList<>();
        int totalDeduction = 0;
        StringBuilder reasonBuilder = new StringBuilder();

        OutcomeExpected outcome = testCase.getExpected().getOutcome();
        if (outcome == null) {
            return GraderResult.success();
        }

        String workspacePath = context.getWorkspacePath();
        if (workspacePath == null || workspacePath.isBlank()) {
            return GraderResult.failed(
                    testCase.getScoring().getResultIncorrectPenalty(),
                    "未配置工作空间路径",
                    List.of("workspacePath 为空"));
        }

        // 检查 file_exists
        String fileExists = outcome.getFileExists();
        if (fileExists != null && !fileExists.isBlank()) {
            Path filePath = resolvePath(workspacePath, fileExists);
            if (!Files.exists(filePath)) {
                totalDeduction += testCase.getScoring().getResultIncorrectPenalty();
                reasonBuilder.append("预期文件不存在: ").append(fileExists).append("; ");
                evidence.add("文件不存在: " + filePath);
            } else {
                evidence.add("文件存在: " + fileExists);
            }
        }

        // 检查 file_content_contains / file_content_not_contains（文件路径取自 file_exists）
        String contentContains = outcome.getFileContentContains();
        String contentNotContains = outcome.getFileContentNotContains();
        if ((contentContains != null && !contentContains.isBlank())
                || (contentNotContains != null && !contentNotContains.isBlank())) {
            String filePath = fileExists;
            if (filePath == null) {
                totalDeduction += testCase.getScoring().getResultIncorrectPenalty();
                reasonBuilder.append("file_content_contains/not_contains 需要指定 file_exists 文件路径").append("; ");
                evidence.add("未指定文件路径");
            } else {
                Path fullPath = resolvePath(workspacePath, filePath);
                try {
                    if (!Files.exists(fullPath)) {
                        totalDeduction += testCase.getScoring().getResultIncorrectPenalty();
                        reasonBuilder.append("预期文件不存在: ").append(filePath).append("; ");
                        evidence.add("文件不存在: " + fullPath);
                    } else {
                        String content = Files.readString(fullPath);
                        if (contentContains != null && !contentContains.isBlank()
                                && !content.contains(contentContains)) {
                            totalDeduction += testCase.getScoring().getResultIncorrectPenalty();
                            reasonBuilder.append("文件内容不包含预期字符串: ").append(contentContains).append("; ");
                            evidence.add("文件内容不包含: " + contentContains);
                        } else if (contentContains != null && !contentContains.isBlank()) {
                            evidence.add("文件内容包含: " + contentContains);
                        }
                        if (contentNotContains != null && !contentNotContains.isBlank()
                                && content.contains(contentNotContains)) {
                            totalDeduction += testCase.getScoring().getResultIncorrectPenalty();
                            reasonBuilder.append("文件内容包含禁止字符串: ").append(contentNotContains).append("; ");
                            evidence.add("文件内容包含禁止字符串: " + contentNotContains);
                        } else if (contentNotContains != null && !contentNotContains.isBlank()) {
                            evidence.add("文件内容不含禁止字符串: " + contentNotContains);
                        }
                    }
                } catch (IOException e) {
                    totalDeduction += testCase.getScoring().getResultIncorrectPenalty();
                    reasonBuilder.append("无法读取文件: ").append(e.getMessage()).append("; ");
                    evidence.add("读取文件失败: " + e.getMessage());
                }
            }
        }

        // 检查 dir_exists
        String dirExists = outcome.getDirExists();
        if (dirExists != null && !dirExists.isBlank()) {
            Path dirPath = resolvePath(workspacePath, dirExists);
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                totalDeduction += testCase.getScoring().getResultIncorrectPenalty();
                reasonBuilder.append("预期目录不存在: ").append(dirExists).append("; ");
                evidence.add("目录不存在: " + dirPath);
            } else {
                evidence.add("目录存在: " + dirExists);
            }
        }

        if (totalDeduction == 0) {
            return GraderResult.success(evidence);
        }
        return GraderResult.failed(totalDeduction, reasonBuilder.toString().trim(), evidence);
    }

    /**
     * 解析路径（支持相对路径）
     */
    private Path resolvePath(String workspacePath, String relativePath) {
        // 处理可能以 workspace/ 开头的路径
        String cleanPath = relativePath;
        if (relativePath.startsWith("workspace/")) {
            cleanPath = relativePath.substring("workspace/".length());
        }
        return Paths.get(workspacePath, cleanPath).toAbsolutePath().normalize();
    }
}
