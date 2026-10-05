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

    public Config(MikuMsgPaper plugin) {
        FileConfiguration fc = plugin.getConfig();
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
