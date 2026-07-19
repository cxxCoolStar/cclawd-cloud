package ai.openagent.agent.eval;

import lombok.Data;

/**
 * 评分配置
 */
@Data
public class EvalScoring {

    /**
     * 评分模式：deduction（负分制）或 direct（直接评分）
     */
    private String mode = "deduction";

    /**
     * 最高分数
     */
    private int maxScore = 100;

    /**
     * 通过阈值
     */
    private int passThreshold = 80;

    /**
     * 结果错误惩罚（直接扣至 0 分）
     */
    private int resultIncorrectPenalty = 100;

    /**
     * 过程违规惩罚（每次）
     */
    private int processViolationPenalty = 10;

    /**
     * 效率加分（超额完成时）
     */
    private boolean efficiencyBonus = false;

    /**
     * 每次额外调用的惩罚
     */
    private int efficiencyPenaltyPerExtraCall = 10;
}
