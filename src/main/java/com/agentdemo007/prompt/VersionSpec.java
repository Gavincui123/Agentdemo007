package com.agentdemo007.prompt;

/**
 * 提示词版本规格（收口 sealed）：latest 跟随推荐版本 / 按版本号 / 按标签。
 *
 * <p>镜像 Nacos {@code AiService} 的 {@code getPrompt} / {@code getPromptByVersion} /
 * {@code getPromptByLabel} 三态，避免在调用方裸传字符串。
 */
public sealed interface VersionSpec permits VersionSpec.Latest, VersionSpec.Version, VersionSpec.Label {

    record Latest() implements VersionSpec {}
    record Version(String value) implements VersionSpec {}
    record Label(String value) implements VersionSpec {}

    static Latest latest() { return new Latest(); }
    static Version version(String v) { return new Version(v); }
    static Label label(String l) { return new Label(l); }
}
