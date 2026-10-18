package me.junxiex.mikumsg.velocity;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Velocity 端配置（零依赖 properties，UTF-8 读写）。
 *
 * <p>支持进入/退出/切换三类消息的开关与格式、MikuAuth 认证服名称，
 * 以及服务器别名映射。首次启动时自动生成带注释的默认配置文件。</p>
 *
 * <p>{@link #load()} 可重复调用（reload）：每次先清空状态再解析，
 * 配置文件中缺失的键回到内置默认值，不会残留上次结果。</p>
 */
public final class ProxyConfig {

    private static final String FILE_NAME = "config.properties";
    private static final String ALIAS_PREFIX = "alias.";

    private static final boolean DEFAULT_ENABLED = true;
    private static final String DEFAULT_JOIN_FORMAT =
            "<green>+ <white>%player% <gray>进入了 <aqua>%server%";
    private static final String DEFAULT_LEAVE_FORMAT =
            "<red>- <white>%player% <gray>离开了 <aqua>%server%";
    private static final String DEFAULT_CHANGE_FORMAT =
            "<yellow>⇄ <white>%player% <gray>从 <aqua>%old_server% <gray>切换到 <aqua>%server%";
    private static final String DEFAULT_AUTH_SERVER = "limbo";

    private final Path dataDirectory;
    private final Logger logger;

    private final Properties props = new Properties();

    /**
     * 服务器别名表：键为注册名的小写形态，查询 O(1)。
     * volatile + 整体替换不可变快照：reload 发生在命令线程，而读取发生在
     * 各连接的 Netty 事件线程，原地 clear+put 普通 HashMap 存在并发风险。
     */
    private volatile Map<String, String> aliases = Map.of();

    // 以下字段：reload 在命令线程写，Netty 事件线程读，volatile 保证可见性。
    // 一致性取舍：逐字段 volatile 只保证单字段读写原子性，reload 瞬间读线程可能
    // 看到"新格式 + 旧开关"的跨字段组合。消息播报属展示型数据，单条消息内混用
    // 新旧配置的窗口仅存在于 reload 的毫秒级瞬间、且下一条消息即收敛，
    // 不值得为此引入锁或不可变快照的整体替换开销——属有意的最终一致性设计。
    private volatile boolean joinEnabled = DEFAULT_ENABLED;
    private volatile String joinFormat = DEFAULT_JOIN_FORMAT;

    private volatile boolean leaveEnabled = DEFAULT_ENABLED;
    private volatile String leaveFormat = DEFAULT_LEAVE_FORMAT;

    private volatile boolean changeEnabled = DEFAULT_ENABLED;
    private volatile String changeFormat = DEFAULT_CHANGE_FORMAT;

    private volatile String authServer = DEFAULT_AUTH_SERVER;

    public ProxyConfig(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    /**
     * 加载（或重载）配置文件。synchronized 防止并发 reload（如控制台与
     * 玩家同时执行 /mmsg reload）时 props 的 clear+load 相互踩踏。
     *
     * <p>任何解析失败（文件读不出、内容非法）都<b>不向外抛</b>，只记日志并沿用当前
     * 配置：本方法在 {@code onInitialize} 与 {@code /mmsg reload} 两条路径上调用，
     * 抛出会分别导致"插件看似启用却毫无功能"与"命令中断"这类难排查的故障。</p>
     *
     * @return true = 已按磁盘内容生效；false = 读取/解析失败，已沿用当前配置。
     *         调用方（{@code /mmsg reload}）据此如实回执，避免"报成功但没生效"。
     */
    public synchronized boolean load() {
        Path file = dataDirectory.resolve(FILE_NAME);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                writeDefault(file);
            }
            // 重新加载前清空：Properties 是累加语义，不移除旧键会导致
            // 配置项删除后残留上次的值
            props.clear();
            // 先整文件读成字符串再解析，而不是直接 load(Reader)：这样可以剥掉
            // 文件头的 UTF-8 BOM。Windows 记事本等编辑器保存的 UTF-8 会带 BOM，
            // Properties 会把 U+FEFF 当成第一个键名的一部分（首键变成
            // 带 BOM 前缀的 join.enabled），导致首行配置被静默忽略。
            String text = stripBom(Files.readString(file, StandardCharsets.UTF_8));
            // 只把"读文件 + 解析内容"包在捕获里：解析失败（IOException / 内容非法的
            // IllegalArgumentException）都不外抛，沿用当前配置。parse() 刻意留在 try 之外，
            // 使其自身的编程缺陷（如 NPE）能正常暴露，不会被误当作配置文件问题而静默吞掉。
            try {
                props.load(new StringReader(text));
            } catch (IOException | RuntimeException ex) {
                // 首次加载即遇错时为内置默认值，reload 失败时沿用上一次的配置。
                // 内容非法（如反斜杠 u 后跟的不是 4 位十六进制）时 Properties.load 抛的是
                // IllegalArgumentException 而非 IOException，故必须一并兜住：否则异常会冲出
                // onInitialize，使监听器与命令全部注册不上，表现为插件"没有反应"。
                logger.error("读取 MikuMsg 配置失败，沿用当前配置：{}", ex.getMessage());
                return false;
            }
            parse();
            return true;
        } catch (IOException ex) {
            logger.error("读取 MikuMsg 配置失败，沿用当前配置：{}", ex.getMessage());
            return false;
        }
    }

    /** 去掉可能存在的 UTF-8 BOM（U+FEFF）。 */
    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private void parse() {
        // 先重置为内置默认值：缺失的键在 reload 后应回到默认，
        // 而不是残留上一次的解析结果
        joinEnabled = DEFAULT_ENABLED;
        joinFormat = DEFAULT_JOIN_FORMAT;
        leaveEnabled = DEFAULT_ENABLED;
        leaveFormat = DEFAULT_LEAVE_FORMAT;
        changeEnabled = DEFAULT_ENABLED;
        changeFormat = DEFAULT_CHANGE_FORMAT;
        authServer = DEFAULT_AUTH_SERVER;

        joinEnabled = bool("join.enabled", joinEnabled);
        joinFormat = str("join.format", joinFormat);
        leaveEnabled = bool("leave.enabled", leaveEnabled);
        leaveFormat = str("leave.format", leaveFormat);
        changeEnabled = bool("change.enabled", changeEnabled);
        changeFormat = str("change.format", changeFormat);
        authServer = str("auth-server", authServer);
        loadAliases();
    }

    private void loadAliases() {
        Map<String, String> snapshot = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.regionMatches(true, 0, ALIAS_PREFIX, 0, ALIAS_PREFIX.length())
                    && key.length() > ALIAS_PREFIX.length()) {
                String serverName = key.substring(ALIAS_PREFIX.length());
                String alias = props.getProperty(key);
                if (alias != null && !alias.isEmpty()) {
                    snapshot.put(serverName.toLowerCase(Locale.ROOT), alias);
                }
            }
        }
        aliases = Map.copyOf(snapshot);
    }

    /**
     * 解析布尔开关。
     *
     * <p>除 {@code true}/{@code false} 外，额外接受 {@code on/off}、{@code yes/no}、
     * {@code 1/0} 这些在 properties 文件里极常见的写法。{@link Boolean#parseBoolean}
     * 对它们一律返回 false——即管理员写 {@code join.enabled=yes} 会把进出消息<b>静默关掉</b>，
     * 且日志里没有任何提示，属难以排查的配置陷阱。无法识别的值回落默认值并告警。</p>
     */
    private boolean bool(String key, boolean def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> {
                logger.warn("MikuMsg 配置项 {} 的值 \"{}\" 不是合法布尔值，已使用默认值 {}。", key, v, def);
                yield def;
            }
        };
    }

    /**
     * 解析字符串配置项。
     *
     * <p>按 {@code trim()} 后判空：properties 的 {@code key=   } 会解析成若干空格，
     * 若只判 {@code isEmpty()} 就会被当作"已配置"，例如 {@code auth-server=   } 会让
     * 认证服判定永远不成立，未登录玩家反而被照常播报。</p>
     */
    private String str(String key, String def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        String trimmed = v.trim();
        return trimmed.isEmpty() ? def : trimmed;
    }

    /** 服务器别名：大小写不敏感匹配注册名，未配置别名时返回原始名。 */
    public String alias(String serverName) {
        if (serverName == null) {
            return "";
        }
        String alias = aliases.get(serverName.toLowerCase(Locale.ROOT));
        return alias != null ? alias : serverName;
    }

    public boolean isJoinEnabled() {
        return joinEnabled;
    }

    public String joinFormat() {
        return joinFormat;
    }

    public boolean isLeaveEnabled() {
        return leaveEnabled;
    }

    public String leaveFormat() {
        return leaveFormat;
    }

    public boolean isChangeEnabled() {
        return changeEnabled;
    }

    public String changeFormat() {
        return changeFormat;
    }

    public String authServer() {
        return authServer;
    }

    private void writeDefault(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# MikuMsg (Velocity) 配置\n");
        sb.append("# 文本采用 MiniMessage 标签格式（Velocity 内置 Adventure）。\n");
        sb.append("# 占位符：%player% 玩家名，%server% 当前服务器，%old_server% 切换前的服务器。\n\n");
        sb.append("join.enabled=").append(DEFAULT_ENABLED).append('\n');
        sb.append("join.format=").append(DEFAULT_JOIN_FORMAT).append("\n\n");
        sb.append("leave.enabled=").append(DEFAULT_ENABLED).append('\n');
        sb.append("leave.format=").append(DEFAULT_LEAVE_FORMAT).append("\n\n");
        sb.append("change.enabled=").append(DEFAULT_ENABLED).append('\n');
        sb.append("change.format=").append(DEFAULT_CHANGE_FORMAT).append("\n\n");
        sb.append("# MikuAuth 认证服名称（与 MikuAuth config.yml 的 server.auth-server 保持一致）。\n");
        sb.append("# 未登录玩家滞留在该服期间不播报任何消息；登录完成被送往正式服时按\"进入\"补报。\n");
        sb.append("auth-server=").append(DEFAULT_AUTH_SERVER).append("\n\n");
        sb.append("# 服务器显示名别名（可选），格式：alias.<服务器注册名>=<显示名>\n");
        sb.append("#alias.survival=生存服\n");
        sb.append("#alias.hub=大厅\n");

        try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(sb.toString());
        }
        logger.info("已生成默认配置文件：{}", file);
    }
}
