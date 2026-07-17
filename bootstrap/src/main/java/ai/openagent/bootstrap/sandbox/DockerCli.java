package ai.openagent.bootstrap.sandbox;

import java.time.Duration;
import java.util.List;

/**
 * docker CLI 封装（V4 方案 4.1：可替换接口，无 Docker 环境时用 fake 单测）
 *
 * <p>
 * 实现只允许调用 docker CLI，不得提供宿主机 shell 执行能力
 * （V4 方案 4.5 安全红线 1）
 * </p>
 */
public interface DockerCli {

    /**
     * 一次 docker CLI 调用的结果
     *
     * @param exitCode 进程退出码；{@link #TIMED_OUT} 表示超时强制终止
     * @param output   stdout+stderr 合并文本
     */
    record CliResult(int exitCode, String output) {

        /**
         * 超时强制终止时的占位退出码
         */
        public static final int TIMED_OUT = -1;

        public boolean success() {
            return exitCode == 0;
        }

        public boolean timedOut() {
            return exitCode == TIMED_OUT;
        }
    }

    /**
     * 执行 docker 命令（无超时，用于 create/start/inspect/rm 等管理操作）
     */
    CliResult run(List<String> args);

    /**
     * 执行 docker 命令并在超时后强制终止（用于 docker exec）
     */
    CliResult run(List<String> args, Duration timeout);
}
