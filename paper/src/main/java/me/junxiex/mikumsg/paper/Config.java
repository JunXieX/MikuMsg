package me.junxiex.mikumsg.paper;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Paper 端配置封装。
 *
 * <p>不可变对象：构造时一次性解析全部配置项，字段均为 final。
 * 重载时整体替换 {@code MikuMsgPaper} 中的引用（volatile），
 * 保证跨线程读取的可见性与一致性。</p>
 */
public final class Config {

    private final boolean suppressJoin;
    private final boolean suppressQuit;

    /** 从插件已加载的配置构造（首次启动 / 配置文件缺失时回填默认值用）。 */
    public Config(MikuMsgPaper plugin) {
        this(plugin.getConfig());
    }

    /**
     * 从给定的配置对象构造快照。
     *
     * <p>两处用途：{@link #Config(MikuMsgPaper)} 传入 Bukkit 托管的 {@code getConfig()}；
     * {@code MikuMsgPaper#cfg()} 在初始化失败、尚无配置快照时传入一份空配置作兜底。</p>
     */
    public Config(FileConfiguration fc) {
        suppressJoin = fc.getBoolean("suppress.join-message", true);
        suppressQuit = fc.getBoolean("suppress.quit-message", true);
    }

    public boolean suppressJoin() {
        return suppressJoin;
    }

    public boolean suppressQuit() {
        return suppressQuit;
    }
}
