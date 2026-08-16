package net.wifil.mcmultilogin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置读取。
 *
 * <p><b>与 Fabric 版的差异</b>：原 {@code ModConfig} 自己用 Gson 读写
 * {@code config/mc-multilogin-compat.json}；Bukkit 有标准的 YAML 配置体系
 * （{@code config.yml} + {@code saveDefaultConfig()} + {@code reloadConfig()}），
 * 所以这里改为封装 {@link FileConfiguration}，让服主用惯常方式改配置、
 * 并支持 {@code /multilogin reload} 热重载。</p>
 *
 * <p>字段与原版一一对应，仅命名改成 YAML 习惯的中划线风格。</p>
 */
public final class PluginConfig {

    private final String apiUrl;
    private final boolean autoRename;
    private final int timeoutSeconds;
    private final boolean shutdownOnFailure;
    private final boolean debug;

    private PluginConfig(String apiUrl, boolean autoRename, int timeoutSeconds,
                         boolean shutdownOnFailure, boolean debug) {
        this.apiUrl = apiUrl;
        this.autoRename = autoRename;
        this.timeoutSeconds = timeoutSeconds;
        this.shutdownOnFailure = shutdownOnFailure;
        this.debug = debug;
    }

    /** 从 Bukkit 的 FileConfiguration 载入，缺失项一律取安全默认值。 */
    public static PluginConfig from(FileConfiguration cfg) {
        String url = cfg.getString("api-url", "");
        if (url == null) {
            url = "";
        }
        url = url.trim();

        return new PluginConfig(
                url,
                cfg.getBoolean("auto-rename", true),
                cfg.getInt("request-timeout-seconds", 10),
                cfg.getBoolean("shutdown-on-failure", false),
                cfg.getBoolean("debug", false)
        );
    }

    /** 认证服务根地址。为空表示未配置 —— 此时插件不挂钩，只提示服主去填。 */
    public String apiUrl() {
        return apiUrl;
    }

    /** 是否在遇到 DUPLICATE_NAME 时用服务端给的 availableId 自动改名重试。 */
    public boolean autoRename() {
        return autoRename;
    }

    /** HTTP 超时（秒）。 */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** 挂钩失败时是否直接关服（防止「以为在验证其实没验证」的安全事故）。 */
    public boolean shutdownOnFailure() {
        return shutdownOnFailure;
    }

    /** 调试日志开关。 */
    public boolean debug() {
        return debug;
    }

    /** 是否已配置认证地址。 */
    public boolean isConfigured() {
        return !apiUrl.isEmpty();
    }

    @Override
    public String toString() {
        return "PluginConfig{apiUrl='" + apiUrl + "', autoRename=" + autoRename
                + ", timeoutSeconds=" + timeoutSeconds
                + ", shutdownOnFailure=" + shutdownOnFailure
                + ", debug=" + debug + '}';
    }
}
