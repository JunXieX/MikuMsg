package me.junxiex.mikumsg.velocity;

import cn.miku.auth.api.MikuAuthApi;
import cn.miku.auth.api.MikuAuthProvider;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * MikuAuth 桥接：通过官方对外 API（{@link MikuAuthProvider}）查询玩家认证状态。
 *
 * <p>MikuAuth 3.6.0+ 在初始化收尾时注册 API 实现、代理关停时撤销。API 查询
 * 线程安全、只读内存，批量假玩家压测下无 IO 与锁竞争热点。</p>
 *
 * <p>本类对 {@code cn.miku.auth.api} 的引用是 compileOnly——运行时类由 MikuAuth
 * 插件的类加载器提供。若服务器上 MikuAuth 版本过旧（&lt; 3.6.0，无 API 类），
 * 触碰这些类会抛 {@code NoClassDefFoundError}，在 {@link #discover} 中被捕获并
 * 降级为"不过滤"，保证 MikuMsg 在任何 MikuAuth 版本组合下都能正常工作。</p>
 *
 * <p>API 实例在 {@link #discover} 时获取并持有：Velocity 不支持单插件热重载，
 * 实现只在代理关停时撤销，而关停后本插件不再处理事件，缓存实例无失效风险。</p>
 */
public final class MikuAuthBridge {

    private final MikuAuthApi api;

    private MikuAuthBridge(MikuAuthApi api) {
        this.api = api;
    }

    /**
     * 探测 MikuAuth 官方 API。
     *
     * @return 桥接实例（持有 API 实现）；MikuAuth 未安装、未就绪或版本过旧时
     *         返回 null，调用方据此关闭认证过滤
     */
    public static MikuAuthBridge discover(ProxyServer proxy, Logger logger) {
        if (proxy.getPluginManager().getPlugin("mikuauth").isEmpty()) {
            logger.info("未检测到 MikuAuth，所有玩家进出消息照常播报。");
            return null;
        }
        try {
            MikuAuthApi found = MikuAuthProvider.get().orElse(null);
            if (found == null) {
                logger.warn("MikuAuth 已安装但 API 未就绪，未登录玩家的消息将无法过滤。");
                return null;
            }
            logger.info("已接入 MikuAuth 官方 API，未认证玩家的进出/切换消息将被过滤。");
            return new MikuAuthBridge(found);
        } catch (LinkageError err) {
            // MikuAuth 版本过旧（< 3.6.0）：API 类不存在
            logger.warn("MikuAuth 版本过旧（缺少对外 API，需 3.6.0+），未登录玩家的消息将无法过滤。");
            return null;
        }
    }

    /**
     * 玩家是否已完成登录（正版/基岩/会话免密/密码登录均为 true）。
     *
     * @param playerId 玩家 UUID
     * @return true = 已认证，可播报其消息；false = 未认证（含假玩家压测连接），应过滤
     */
    public boolean isAuthenticated(UUID playerId) {
        return api.isAuthenticated(playerId);
    }
}
